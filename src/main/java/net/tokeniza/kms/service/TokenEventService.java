package net.tokeniza.kms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.TokenEventRequestDto;
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
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenEventService {

    private final Web3j web3j;
    private final KmsSigner platformSigner;
    private final AppProperties props;

    public record EventResult(String txHash, long blockNumber, String gasUsed) {}

    public EventResult executeOperation(TokenEventRequestDto req) throws Exception {
        String contractAddress = req.getToken().getAddress();
        String operationType   = req.getOperation().getType();
        int decimals           = req.getToken().getDecimals();

        String encodedFunction = switch (operationType.toLowerCase()) {
            case "mint" -> encodeMint(req.getOperation().getToAddress(),
                    req.getOperation().getAmount(), decimals);
            case "burn" -> encodeBurn(req.getOperation().getFromAddress(),
                    req.getOperation().getAmount(), decimals);
            case "pause"   -> encodeNoArg("pause");
            case "unpause" -> encodeNoArg("unpause");
            default -> throw new IllegalArgumentException("Unknown operation: " + operationType);
        };

        log.info("Token event: op={} contract={}", operationType, contractAddress);

        String from = platformSigner.getAddress();
        String txHash = sendRawTransaction(from, contractAddress, BigInteger.ZERO, encodedFunction);
        return waitForReceipt(txHash);
    }

    // ── Encoders ──────────────────────────────────────────────────────────────

    private String encodeMint(String to, String amount, int decimals) {
        BigInteger amountWei = new BigDecimal(amount).multiply(BigDecimal.TEN.pow(decimals)).toBigInteger();
        return FunctionEncoder.encode(new Function("mint",
                Arrays.asList(new Address(to), new Uint256(amountWei)),
                List.of(new TypeReference<Bool>() {})));
    }

    private String encodeBurn(String from, String amount, int decimals) {
        BigInteger amountWei = new BigDecimal(amount).multiply(BigDecimal.TEN.pow(decimals)).toBigInteger();
        return FunctionEncoder.encode(new Function("burn",
                Arrays.asList(new Address(from), new Uint256(amountWei)),
                List.of(new TypeReference<Bool>() {})));
    }

    private String encodeNoArg(String name) {
        return FunctionEncoder.encode(new Function(name, Collections.emptyList(), Collections.emptyList()));
    }

    // ── Transaction helpers ───────────────────────────────────────────────────

    private String sendRawTransaction(String from, String to, BigInteger value, String data) throws Exception {
        AppProperties.Dlt dlt = props.getDlt();

        BigInteger nonce = web3j.ethGetTransactionCount(from, DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();

        RawTransaction rawTx = RawTransaction.createTransaction(
                dlt.getChainId(), nonce,
                BigInteger.valueOf(dlt.getGasLimit()), to, value, data,
                BigInteger.valueOf(dlt.getMaxPriorityFeePerGas()),
                BigInteger.valueOf(dlt.getMaxFeePerGas())
        );

        byte[] encodedForSigning = TransactionEncoder.encode(rawTx, dlt.getChainId());
        byte[] txHash = Hash.sha3(encodedForSigning);
        Sign.SignatureData sig = platformSigner.sign(txHash);
        byte[] signedTx = TransactionEncoder.encode(rawTx, sig);

        EthSendTransaction response = web3j.ethSendRawTransaction(Numeric.toHexString(signedTx)).send();
        if (response.hasError()) throw new RuntimeException("RPC error: " + response.getError().getMessage());

        log.info("Transaction submitted: {}", response.getTransactionHash());
        return response.getTransactionHash();
    }

    private EventResult waitForReceipt(String txHash) throws Exception {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(2_000);
            EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(txHash).send();
            Optional<TransactionReceipt> opt = resp.getTransactionReceipt();
            if (opt.isPresent()) {
                TransactionReceipt r = opt.get();
                if (!"0x1".equals(r.getStatus())) throw new RuntimeException("Transaction reverted: " + txHash);
                return new EventResult(txHash, r.getBlockNumber().longValue(), r.getGasUsed().toString());
            }
        }
        throw new RuntimeException("Receipt timeout: " + txHash);
    }
}
