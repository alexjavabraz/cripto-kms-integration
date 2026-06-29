package net.tokeniza.kms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.kms.KmsSigner;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;
import software.amazon.awssdk.services.kms.KmsClient;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenTransferService {

    private final Web3j web3j;
    private final KmsSigner platformSigner;   // platform wallet (injected by KmsClientConfig)
    private final KmsClient kmsClient;
    private final AppProperties props;

    public record TransferResult(String txHash, long blockNumber, String gasUsed) {}

    /**
     * Executes an ERC-20 transfer(address,uint256) using the platform KMS wallet.
     */
    public TransferResult executeTransfer(String contractAddress, String toAddress,
                                          String amount, int decimals) throws Exception {
        return executeTransferWithSigner(platformSigner, contractAddress, toAddress, amount, decimals);
    }

    /**
     * Executes an ERC-20 transfer using a user's KMS wallet (identified by KMS key ID).
     * The userWalletId field received from the BFF message is the KMS Key ARN/ID.
     */
    public TransferResult executeUserTransfer(String userKmsKeyId, String contractAddress,
                                              String toAddress, String amount, int decimals) throws Exception {
        KmsSigner userSigner = new KmsSigner(kmsClient, userKmsKeyId);
        return executeTransferWithSigner(userSigner, contractAddress, toAddress, amount, decimals);
    }

    /**
     * Executes a native token transfer (for gas funding new wallets).
     */
    public TransferResult sendNative(String toAddress, String amountEth) throws Exception {
        String from = platformSigner.getAddress();
        BigInteger amountWei = Convert.toWei(amountEth, Convert.Unit.ETHER).toBigInteger();
        log.info("Sending {} native token from {} to {}", amountEth, from, toAddress);
        String txHash = sendRawTransaction(platformSigner, from, toAddress, amountWei, "0x");
        return waitForReceipt(txHash);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private TransferResult executeTransferWithSigner(KmsSigner signer, String contractAddress,
                                                     String toAddress, String amount,
                                                     int decimals) throws Exception {
        String from = signer.getAddress();

        // Parse amount as token units — convert to wei-equivalent based on decimals
        BigDecimal amountDecimal = new BigDecimal(amount);
        BigInteger amountWei = amountDecimal
                .multiply(BigDecimal.TEN.pow(decimals))
                .toBigInteger();

        log.info("ERC-20 transfer: from={} contract={} to={} amount={} ({}wei)",
                from, contractAddress, toAddress, amount, amountWei);

        Function function = new Function(
                "transfer",
                Arrays.asList(new Address(toAddress), new Uint256(amountWei)),
                List.of(new TypeReference<Bool>() {})
        );
        String encodedFunction = FunctionEncoder.encode(function);

        String txHash = sendRawTransaction(signer, from, contractAddress, BigInteger.ZERO, encodedFunction);
        return waitForReceipt(txHash);
    }

    private String sendRawTransaction(KmsSigner signer, String from, String to,
                                      BigInteger value, String data) throws Exception {
        AppProperties.Dlt dlt = props.getDlt();

        BigInteger nonce = web3j
                .ethGetTransactionCount(from, DefaultBlockParameterName.PENDING)
                .send()
                .getTransactionCount();

        BigInteger gasLimit = BigInteger.valueOf(dlt.getGasLimit());
        BigInteger maxFeePerGas = BigInteger.valueOf(dlt.getMaxFeePerGas());
        BigInteger maxPriorityFeePerGas = BigInteger.valueOf(dlt.getMaxPriorityFeePerGas());

        RawTransaction rawTx = RawTransaction.createTransaction(
                dlt.getChainId(), nonce, gasLimit, to, value, data,
                maxPriorityFeePerGas, maxFeePerGas
        );

        // EIP-1559: chainId is already in the tx fields — encode without signature for signing hash
        byte[] encodedForSigning = TransactionEncoder.encode(rawTx);
        byte[] txHash = Hash.sha3(encodedForSigning);

        Sign.SignatureData sig = signer.sign(txHash);
        byte[] signedTx = TransactionEncoder.encode(rawTx, sig);

        EthSendTransaction response = web3j
                .ethSendRawTransaction(Numeric.toHexString(signedTx))
                .send();

        if (response.hasError()) {
            throw new RuntimeException("RPC error: " + response.getError().getMessage());
        }

        String hash = response.getTransactionHash();
        log.info("Transaction submitted: {}", hash);
        return hash;
    }

    private TransferResult waitForReceipt(String txHash) throws Exception {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(2_000);
            EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(txHash).send();
            Optional<TransactionReceipt> opt = resp.getTransactionReceipt();
            if (opt.isPresent()) {
                TransactionReceipt r = opt.get();
                if (!"0x1".equals(r.getStatus())) {
                    throw new RuntimeException("Transaction reverted: " + txHash);
                }
                log.info("Confirmed: hash={} block={} gas={}", txHash, r.getBlockNumber(), r.getGasUsed());
                return new TransferResult(txHash, r.getBlockNumber().longValue(), r.getGasUsed().toString());
            }
        }
        throw new RuntimeException("Receipt timeout for tx: " + txHash);
    }
}
