package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.CreationRequestDto;
import net.tokeniza.kms.kms.KmsSigner;
import net.tokeniza.kms.service.SnsPublisher;
import org.springframework.stereotype.Component;
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

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreationConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final Web3j web3j;
    private final KmsSigner platformSigner;
    private final SnsPublisher snsPublisher;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Boolean> processedKeys = new ConcurrentHashMap<>();

    void handle(String body) {
        long startMs = System.currentTimeMillis();
        CreationRequestDto req = null;
        try {
            req = objectMapper.readValue(body, CreationRequestDto.class);

            if (processedKeys.putIfAbsent(req.getIdempotencyKey(), true) != null) {
                log.warn("Duplicate creation request: {}", req.getIdempotencyKey());
                snsPublisher.publish(req.getIdempotencyKey(),
                        errorPayload(req, "DUPLICATE_REQUEST",
                                "Idempotency key already processed: " + req.getIdempotencyKey(),
                                System.currentTimeMillis() - startMs));
                return;
            }

            log.info("Deploying token: standard={} network={} idempotencyKey={}",
                    req.getToken().getStandard(), req.getNetwork().getName(), req.getIdempotencyKey());

            String bytecode = resolveConstructorBytecode(req);
            String txHash = deployContract(bytecode);
            TransactionReceipt receipt = waitForReceipt(txHash);

            log.info("Token deployed: contract={} txHash={}", receipt.getContractAddress(), txHash);
            snsPublisher.publish(req.getIdempotencyKey(),
                    successPayload(req, receipt.getContractAddress(), txHash, receipt, System.currentTimeMillis() - startMs));

        } catch (Exception e) {
            log.error("Token creation failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                snsPublisher.publish(req.getIdempotencyKey(),
                        errorPayload(req, "DEPLOYMENT_FAILED", e.getMessage(), System.currentTimeMillis() - startMs));
            }
        }
    }

    // ── Bytecode ──────────────────────────────────────────────────────────────

    private String resolveConstructorBytecode(CreationRequestDto req) {
        String standard = req.getToken().getStandard().toUpperCase();
        String bytecode = System.getenv("BYTECODE_" + standard);
        if (bytecode == null || bytecode.isBlank()) {
            throw new IllegalStateException("Missing env var BYTECODE_" + standard);
        }
        return appendConstructorArgs(bytecode, req);
    }

    private String appendConstructorArgs(String bytecode, CreationRequestDto req) {
        return switch (req.getToken().getStandard().toUpperCase()) {
            case "ERC20" -> bytecode + encodeErc20Constructor(
                    req.getToken().getName(), req.getToken().getSymbol(),
                    req.getParams() != null && req.getParams().getErc20() != null
                            ? req.getParams().getErc20().getDecimals() : 18,
                    req.getToken().getOwnerAddress());
            case "ERC721" -> bytecode + encodeErc721Constructor(
                    req.getToken().getName(), req.getToken().getSymbol(), req.getToken().getOwnerAddress());
            case "ERC1155" -> bytecode + encodeErc1155Constructor(
                    req.getParams() != null && req.getParams().getErc1155() != null
                            ? req.getParams().getErc1155().getUri() : "",
                    req.getToken().getOwnerAddress());
            default -> throw new IllegalArgumentException("Unsupported standard: " + req.getToken().getStandard());
        };
    }

    private String encodeErc20Constructor(String name, String symbol, int decimals, String owner) {
        return abiEncodeStrings(name, symbol) + pad32(BigInteger.valueOf(decimals).toString(16))
                + pad32(owner.toLowerCase().replace("0x", ""));
    }

    private String encodeErc721Constructor(String name, String symbol, String owner) {
        return abiEncodeStrings(name, symbol) + pad32(owner.toLowerCase().replace("0x", ""));
    }

    private String encodeErc1155Constructor(String uri, String owner) {
        byte[] uriBytes = uri.getBytes();
        return pad32(Integer.toHexString(64))
                + pad32(owner.toLowerCase().replace("0x", ""))
                + pad32(Integer.toHexString(uriBytes.length))
                + Numeric.toHexStringNoPrefix(uriBytes)
                + "0".repeat((32 - uriBytes.length % 32) % 32 * 2);
    }

    private String abiEncodeStrings(String s1, String s2) {
        byte[] b1 = s1.getBytes(), b2 = s2.getBytes();
        return pad32(Integer.toHexString(64))
                + pad32(Integer.toHexString(64 + 32 + ceil32(b1.length)))
                + pad32(Integer.toHexString(b1.length))
                + Numeric.toHexStringNoPrefix(b1) + "0".repeat((32 - b1.length % 32) % 32 * 2)
                + pad32(Integer.toHexString(b2.length))
                + Numeric.toHexStringNoPrefix(b2) + "0".repeat((32 - b2.length % 32) % 32 * 2);
    }

    private static int ceil32(int n) { return n == 0 ? 32 : ((n + 31) / 32) * 32; }
    private static String pad32(String hex) { return "0".repeat(64 - hex.length()) + hex; }

    // ── Deploy ────────────────────────────────────────────────────────────────

    private String deployContract(String bytecode) throws Exception {
        AppProperties.Dlt dlt = props.getDlt();
        String from = platformSigner.getAddress();
        BigInteger nonce = web3j.ethGetTransactionCount(from, DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();

        RawTransaction rawTx = RawTransaction.createContractTransaction(
                dlt.getChainId(), nonce, BigInteger.valueOf(dlt.getGasLimit()),
                BigInteger.ZERO, bytecode,
                BigInteger.valueOf(dlt.getMaxPriorityFeePerGas()),
                BigInteger.valueOf(dlt.getMaxFeePerGas())
        );

        byte[] encodedForSigning = TransactionEncoder.encode(rawTx, dlt.getChainId());
        Sign.SignatureData sig = platformSigner.sign(Hash.sha3(encodedForSigning));
        byte[] signedTx = TransactionEncoder.encode(rawTx, sig);

        EthSendTransaction response = web3j.ethSendRawTransaction(Numeric.toHexString(signedTx)).send();
        if (response.hasError()) throw new RuntimeException("Deploy failed: " + response.getError().getMessage());
        return response.getTransactionHash();
    }

    private TransactionReceipt waitForReceipt(String txHash) throws Exception {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(2_000);
            EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(txHash).send();
            Optional<TransactionReceipt> opt = resp.getTransactionReceipt();
            if (opt.isPresent()) {
                TransactionReceipt r = opt.get();
                if (!"0x1".equals(r.getStatus())) throw new RuntimeException("Deploy reverted: " + txHash);
                return r;
            }
        }
        throw new RuntimeException("Deploy receipt timeout: " + txHash);
    }

    // ── Responses ─────────────────────────────────────────────────────────────

    private Map<String, Object> successPayload(CreationRequestDto req, String contractAddress,
                                               String txHash, TransactionReceipt receipt, long durationMs) {
        String explorerUrl = props.getDlt().getExplorerUrl();
        Map<String, Object> r = new HashMap<>();
        r.put("event", "token.creation.succeeded");
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("timestamp", Instant.now().toString());
        r.put("network", Map.of("name", req.getNetwork().getName(), "chainId", req.getNetwork().getChainId()));
        r.put("token", Map.of("standard", req.getToken().getStandard(),
                "name", req.getToken().getName(), "symbol", req.getToken().getSymbol(),
                "contractAddress", contractAddress));
        r.put("deployment", Map.of("contractAddress", contractAddress, "transactionHash", txHash,
                "blockNumber", receipt.getBlockNumber().longValue(),
                "deployerAddress", platformSigner.getAddress(),
                "gasUsed", receipt.getGasUsed().toString(),
                "effectiveGasPrice", receipt.getEffectiveGasPrice() != null ? receipt.getEffectiveGasPrice().toString() : "0"));
        if (!explorerUrl.isBlank()) {
            r.put("explorer", Map.of("transactionUrl", explorerUrl + "/tx/" + txHash,
                    "contractUrl", explorerUrl + "/address/" + contractAddress));
        }
        r.put("metadata", Map.of("correlationId", req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY, "durationMs", durationMs));
        return r;
    }

    private Map<String, Object> errorPayload(CreationRequestDto req, String code, String message, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "token.creation.failed");
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("timestamp", Instant.now().toString());
        r.put("network", Map.of("name", req.getNetwork() != null ? req.getNetwork().getName() : "unknown", "chainId", 0));
        r.put("token", Map.of("standard", req.getToken() != null ? req.getToken().getStandard() : "unknown"));
        r.put("error", Map.of("code", code, "message", message, "retryable", false));
        r.put("metadata", Map.of("correlationId", req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY, "durationMs", durationMs));
        return r;
    }
}
