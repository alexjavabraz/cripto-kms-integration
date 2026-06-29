package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.CreationRequestDto;
import net.tokeniza.kms.kms.KmsSigner;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

/**
 * Listens to token creation requests (ERC-20/721/1155 deployment).
 *
 * CONTRACT BYTECODE: The compiled bytecode for each token standard must be
 * provided via the ERC20_BYTECODE / ERC721_BYTECODE / ERC1155_BYTECODE
 * environment variables.  These are the constructor-encoded bytecodes produced
 * by compiling your OpenZeppelin-based contracts.
 *
 * Example (hardhat): after `npx hardhat compile`, extract from
 *   artifacts/contracts/BRLNToken.sol/BRLNToken.json → bytecode field.
 *
 * The consumer encodes constructor arguments using ABI encoding and appends
 * them to the bytecode before deployment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreationConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final Web3j web3j;
    private final KmsSigner platformSigner;
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    // In-memory idempotency guard (restart-safe: restarts are rare for creation ops)
    private final ConcurrentHashMap<String, Boolean> processedKeys = new ConcurrentHashMap<>();

    @RabbitListener(queues = "${kms.queue.token-creation}")
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        long startMs = System.currentTimeMillis();
        CreationRequestDto req = null;

        try {
            req = objectMapper.readValue(message.getBody(), CreationRequestDto.class);

            if (processedKeys.putIfAbsent(req.getIdempotencyKey(), true) != null) {
                log.warn("Duplicate creation request ignored: {}", req.getIdempotencyKey());
                publishError(req, "DUPLICATE_REQUEST",
                        "Idempotency key already processed: " + req.getIdempotencyKey(),
                        System.currentTimeMillis() - startMs);
                channel.basicAck(tag, false);
                return;
            }

            log.info("Deploying token: standard={} network={} idempotencyKey={}",
                    req.getToken().getStandard(), req.getNetwork().getName(), req.getIdempotencyKey());

            String bytecode = resolveConstructorBytecode(req);
            String txHash = deployContract(bytecode);
            TransactionReceipt receipt = waitForReceipt(txHash);

            String contractAddress = receipt.getContractAddress();
            log.info("Token deployed: contract={} txHash={}", contractAddress, txHash);

            publishSuccess(req, contractAddress, txHash, receipt, System.currentTimeMillis() - startMs);

        } catch (Exception e) {
            log.error("Token creation failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                publishError(req, "DEPLOYMENT_FAILED", e.getMessage(), System.currentTimeMillis() - startMs);
            }
        } finally {
            channel.basicAck(tag, false);
        }
    }

    // ── Bytecode resolution ───────────────────────────────────────────────────

    private String resolveConstructorBytecode(CreationRequestDto req) {
        String standard = req.getToken().getStandard().toUpperCase();
        String bytecode = System.getenv("BYTECODE_" + standard);
        if (bytecode == null || bytecode.isBlank()) {
            throw new IllegalStateException(
                    "Missing env var BYTECODE_" + standard +
                    " — provide compiled constructor bytecode for " + standard + " deployment");
        }
        // Bytecode already includes ABI-encoded constructor args compiled into it.
        // For dynamic constructor args (name, symbol, decimals, owner), encode and append here.
        return appendConstructorArgs(bytecode, req);
    }

    /**
     * Appends ABI-encoded constructor arguments to the deployment bytecode.
     * Adjust the encoding to match your specific contract constructor signature.
     */
    private String appendConstructorArgs(String bytecode, CreationRequestDto req) {
        // Standard ABI encoding for (string name, string symbol, uint8 decimals, address owner)
        // Each string is encoded as offset + length + data (padded to 32 bytes)
        String standard = req.getToken().getStandard().toUpperCase();
        return switch (standard) {
            case "ERC20" -> bytecode + encodeErc20Constructor(
                    req.getToken().getName(),
                    req.getToken().getSymbol(),
                    req.getParams() != null && req.getParams().getErc20() != null
                            ? req.getParams().getErc20().getDecimals() : 18,
                    req.getToken().getOwnerAddress()
            );
            case "ERC721" -> bytecode + encodeErc721Constructor(
                    req.getToken().getName(),
                    req.getToken().getSymbol(),
                    req.getToken().getOwnerAddress()
            );
            case "ERC1155" -> bytecode + encodeErc1155Constructor(
                    req.getParams() != null && req.getParams().getErc1155() != null
                            ? req.getParams().getErc1155().getUri() : "",
                    req.getToken().getOwnerAddress()
            );
            default -> throw new IllegalArgumentException("Unsupported token standard: " + standard);
        };
    }

    // ── ABI encoding helpers ──────────────────────────────────────────────────

    private String encodeErc20Constructor(String name, String symbol, int decimals, String owner) {
        // (string, string, uint8, address)
        return abiEncodeStrings(name, symbol) +
               pad32(BigInteger.valueOf(decimals).toString(16)) +
               pad32(owner.toLowerCase().replace("0x", ""));
    }

    private String encodeErc721Constructor(String name, String symbol, String owner) {
        // (string, string, address)
        return abiEncodeStrings(name, symbol) +
               pad32(owner.toLowerCase().replace("0x", ""));
    }

    private String encodeErc1155Constructor(String uri, String owner) {
        // (string, address)
        int offset1 = 64; // 2 slots: offset for string + owner address
        byte[] uriBytes = uri.getBytes();
        String encoded = pad32(Integer.toHexString(offset1)) +
                         pad32(owner.toLowerCase().replace("0x", "")) +
                         pad32(Integer.toHexString(uriBytes.length)) +
                         Numeric.toHexStringNoPrefix(uriBytes).concat("0".repeat((32 - uriBytes.length % 32) % 32 * 2));
        return encoded;
    }

    private String abiEncodeStrings(String s1, String s2) {
        byte[] b1 = s1.getBytes(), b2 = s2.getBytes();
        int offset1 = 64; // 2 offsets
        int offset2 = offset1 + 32 + ceil32(b1.length);
        return pad32(Integer.toHexString(offset1)) +
               pad32(Integer.toHexString(offset2)) +
               pad32(Integer.toHexString(b1.length)) +
               Numeric.toHexStringNoPrefix(b1).concat("0".repeat((32 - b1.length % 32) % 32 * 2)) +
               pad32(Integer.toHexString(b2.length)) +
               Numeric.toHexStringNoPrefix(b2).concat("0".repeat((32 - b2.length % 32) % 32 * 2));
    }

    private static int ceil32(int n) { return n == 0 ? 32 : ((n + 31) / 32) * 32; }
    private static String pad32(String hex) { return "0".repeat(64 - hex.length()) + hex; }

    // ── Deployment ────────────────────────────────────────────────────────────

    private String deployContract(String bytecode) throws Exception {
        AppProperties.Dlt dlt = props.getDlt();
        String from = platformSigner.getAddress();

        BigInteger nonce = web3j.ethGetTransactionCount(from, DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();

        RawTransaction rawTx = RawTransaction.createContractTransaction(
                dlt.getChainId(), nonce,
                BigInteger.valueOf(dlt.getGasLimit()),
                BigInteger.ZERO,
                bytecode,
                BigInteger.valueOf(dlt.getMaxPriorityFeePerGas()),
                BigInteger.valueOf(dlt.getMaxFeePerGas())
        );

        byte[] encodedForSigning = TransactionEncoder.encode(rawTx, dlt.getChainId());
        byte[] txHash = Hash.sha3(encodedForSigning);
        Sign.SignatureData sig = platformSigner.sign(txHash);
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

    // ── Response publishing ───────────────────────────────────────────────────

    private void publishSuccess(CreationRequestDto req, String contractAddress,
                                String txHash, TransactionReceipt receipt, long durationMs) {
        String explorerUrl = props.getDlt().getExplorerUrl();
        Map<String, Object> response = new HashMap<>();
        response.put("event", "token.creation.succeeded");
        response.put("idempotencyKey", req.getIdempotencyKey());
        response.put("timestamp", Instant.now().toString());
        response.put("network", Map.of("name", req.getNetwork().getName(), "chainId", req.getNetwork().getChainId()));
        response.put("token", Map.of(
                "standard", req.getToken().getStandard(),
                "name", req.getToken().getName(),
                "symbol", req.getToken().getSymbol(),
                "contractAddress", contractAddress
        ));
        response.put("deployment", Map.of(
                "contractAddress", contractAddress,
                "transactionHash", txHash,
                "blockNumber", receipt.getBlockNumber().longValue(),
                "deployerAddress", platformSigner.getAddress(),
                "gasUsed", receipt.getGasUsed().toString(),
                "effectiveGasPrice", receipt.getEffectiveGasPrice() != null ? receipt.getEffectiveGasPrice().toString() : "0"
        ));
        if (!explorerUrl.isBlank()) {
            response.put("explorer", Map.of(
                    "transactionUrl", explorerUrl + "/tx/" + txHash,
                    "contractUrl", explorerUrl + "/address/" + contractAddress
            ));
        }
        response.put("metadata", Map.of(
                "correlationId", req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY,
                "durationMs", durationMs
        ));

        rabbitTemplate.convertAndSend(props.getExchange().getTokenCreationResponse(), "token.creation.succeeded", response);
    }

    private void publishError(CreationRequestDto req, String code, String message, long durationMs) {
        Map<String, Object> response = new HashMap<>();
        response.put("event", "token.creation.failed");
        response.put("idempotencyKey", req.getIdempotencyKey());
        response.put("timestamp", Instant.now().toString());
        response.put("network", Map.of("name", req.getNetwork() != null ? req.getNetwork().getName() : "unknown", "chainId", 0));
        response.put("token", Map.of("standard", req.getToken() != null ? req.getToken().getStandard() : "unknown"));
        response.put("error", Map.of("code", code, "message", message, "retryable", false));
        response.put("metadata", Map.of(
                "correlationId", req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY,
                "durationMs", durationMs
        ));

        rabbitTemplate.convertAndSend(props.getExchange().getTokenCreationResponse(), "token.creation.failed", response);
    }
}
