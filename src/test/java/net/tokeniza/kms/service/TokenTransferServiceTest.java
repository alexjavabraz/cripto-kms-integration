package net.tokeniza.kms.service;

import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.kms.KmsSigner;
import net.tokeniza.kms.TestCryptoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SignResponse;

import java.math.BigInteger;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenTransferServiceTest {

    @Mock Web3j web3j;
    @Mock KmsClient kmsClient;
    @Mock KmsSigner platformSigner;

    AppProperties props;
    TokenTransferService service;

    ECKeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = Keys.createEcKeyPair();
        props = new AppProperties();
        props.getDlt().setRpcEndpoint("http://localhost:8545");
        props.getDlt().setChainId(1337L);
        props.getDlt().setGasLimit(300_000L);
        props.getDlt().setMaxFeePerGas(0L);
        props.getDlt().setMaxPriorityFeePerGas(0L);
        service = new TokenTransferService(web3j, platformSigner, kmsClient, props);
    }

    @Test
    void executeTransfer_buildsTxAndReturnsReceipt() throws Exception {
        String contract = "0x" + "a".repeat(40);
        String to = "0x" + "b".repeat(40);
        String txHash = "0xdeadbeef";

        when(platformSigner.getAddress()).thenReturn("0x" + Keys.getAddress(keyPair));
        when(platformSigner.sign(any())).thenReturn(fakeSig());

        mockEthCalls(txHash, "0x1");

        TokenTransferService.TransferResult result = service.executeTransfer(contract, to, "100", 18);

        assertThat(result.txHash()).isEqualTo(txHash);
        assertThat(result.blockNumber()).isEqualTo(42L);
        assertThat(result.gasUsed()).isEqualTo("21000");
    }

    @Test
    void executeTransfer_sendsErc20TransferCall() throws Exception {
        String contract = "0x" + "c".repeat(40);
        String to = "0x" + "d".repeat(40);

        when(platformSigner.getAddress()).thenReturn("0x" + Keys.getAddress(keyPair));
        when(platformSigner.sign(any())).thenReturn(fakeSig());
        mockEthCalls("0xabc", "0x1");

        service.executeTransfer(contract, to, "50", 18);

        // verify that sign was called with a 32-byte keccak digest
        ArgumentCaptor<byte[]> digestCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(platformSigner).sign(digestCaptor.capture());
        assertThat(digestCaptor.getValue()).hasSize(32);
    }

    @Test
    void executeTransfer_throwsIfTransactionReverted() throws Exception {
        when(platformSigner.getAddress()).thenReturn("0x" + Keys.getAddress(keyPair));
        when(platformSigner.sign(any())).thenReturn(fakeSig());
        mockEthCalls("0xreverted", "0x0"); // status 0x0 = reverted

        assertThatThrownBy(() -> service.executeTransfer("0x" + "a".repeat(40), "0x" + "b".repeat(40), "1", 18))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("reverted");
    }

    @Test
    void executeUserTransfer_usesUserKmsKey() throws Exception {
        String userKeyId = "arn:aws:kms:us-east-1:123:key/user-key";
        String userAddress = "0x" + Keys.getAddress(keyPair);
        byte[] publicKeyDer = TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey());

        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder().publicKey(SdkBytes.fromByteArray(publicKeyDer)).build());

        when(kmsClient.sign(any(SignRequest.class))).thenAnswer(inv -> {
            SignRequest req = inv.getArgument(0);
            byte[] digest = req.message().asByteArray();
            Sign.SignatureData webSig = Sign.signMessage(digest, keyPair, false);
            byte[] der = TestCryptoUtils.buildDerSignature(
                    new BigInteger(1, webSig.getR()), new BigInteger(1, webSig.getS()));
            return SignResponse.builder().signature(SdkBytes.fromByteArray(der)).build();
        });

        mockEthCalls("0xusertx", "0x1");

        TokenTransferService.TransferResult result = service.executeUserTransfer(
                userKeyId, "0x" + "a".repeat(40), "0x" + "b".repeat(40), "10", 18);

        assertThat(result.txHash()).isEqualTo("0xusertx");
        // KmsClient should have been called for the user key (not the platform signer)
        verify(kmsClient, atLeastOnce()).getPublicKey(any(GetPublicKeyRequest.class));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockEthCalls(String txHash, String status) throws Exception {
        Request countReq = mock(Request.class);
        EthGetTransactionCount count = new EthGetTransactionCount();
        count.setResult("0x0");
        when(countReq.send()).thenReturn(count);
        doReturn(countReq).when(web3j).ethGetTransactionCount(anyString(), any());

        Request sendReq = mock(Request.class);
        EthSendTransaction sendTx = new EthSendTransaction();
        sendTx.setResult(txHash);
        when(sendReq.send()).thenReturn(sendTx);
        doReturn(sendReq).when(web3j).ethSendRawTransaction(anyString());

        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setTransactionHash(txHash);
        receipt.setBlockNumber("0x2a");
        receipt.setGasUsed("0x5208");
        receipt.setStatus(status);

        Request receiptReq = mock(Request.class);
        EthGetTransactionReceipt ethReceipt = new EthGetTransactionReceipt();
        ethReceipt.setResult(receipt);
        when(receiptReq.send()).thenReturn(ethReceipt);
        doReturn(receiptReq).when(web3j).ethGetTransactionReceipt(txHash);
    }

    private Sign.SignatureData fakeSig() {
        // v=27 (recId=0) — web3j 4.12 TransactionEncoder expects 27 or 28
        return new Sign.SignatureData((byte) 27, new byte[32], new byte[32]);
    }
}
