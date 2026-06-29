package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.TokenTransferRequestDto;
import net.tokeniza.kms.service.TokenTransferService;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransferConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final TokenTransferService transferService;
    private final SqsTemplate sqsTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @SqsListener("${kms.sqs.token-transfer-request}")
    public void onMessage(Message<String> message) {
        long startMs = System.currentTimeMillis();
        TokenTransferRequestDto req = null;
        try {
            req = objectMapper.readValue(message.getPayload(), TokenTransferRequestDto.class);
            log.info("Token transfer: idempotencyKey={} network={} to={} amount={}",
                    req.getIdempotencyKey(), req.getNetwork(),
                    req.getTransfer().getToAddress(), req.getTransfer().getAmount());

            TokenTransferService.TransferResult result = transferService.executeTransfer(
                    req.getToken().getContractAddress(),
                    req.getTransfer().getToAddress(),
                    req.getTransfer().getAmount(),
                    req.getToken().getDecimals()
            );

            send(props.getSqs().getTokenTransferResponse(), successPayload(req, result, System.currentTimeMillis() - startMs));
            log.info("Token transfer succeeded: idempotencyKey={} txHash={}", req.getIdempotencyKey(), result.txHash());

        } catch (Exception e) {
            log.error("Token transfer failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                send(props.getSqs().getTokenTransferResponse(), errorPayload(req, e.getMessage(), System.currentTimeMillis() - startMs));
            }
            // Do not re-throw — message is always deleted from SQS (never nack)
        }
    }

    private Map<String, Object> successPayload(TokenTransferRequestDto req,
                                               TokenTransferService.TransferResult result, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "token.transfer.succeeded");
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("timestamp", Instant.now().toString());
        r.put("network", req.getNetwork());
        r.put("requester", Map.of("userId", req.getRequester().getUserId()));
        r.put("token", Map.of("contractAddress", req.getToken().getContractAddress(), "decimals", req.getToken().getDecimals()));
        r.put("transfer", Map.of("toAddress", req.getTransfer().getToAddress(), "amount", req.getTransfer().getAmount()));
        r.put("result", Map.of("txHash", result.txHash(), "blockNumber", result.blockNumber(), "gasUsed", result.gasUsed()));
        r.put("metadata", metadata(req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "", durationMs));
        return r;
    }

    private Map<String, Object> errorPayload(TokenTransferRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "token.transfer.failed");
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("timestamp", Instant.now().toString());
        r.put("network", req.getNetwork());
        r.put("requester", Map.of("userId", req.getRequester() != null ? req.getRequester().getUserId() : "unknown"));
        r.put("token", Map.of("contractAddress", req.getToken() != null ? req.getToken().getContractAddress() : "0x0", "decimals", 18));
        r.put("transfer", Map.of("toAddress", "0x0", "amount", "0"));
        r.put("error", Map.of("code", "EXECUTION_FAILED", "message", errorMessage));
        r.put("metadata", metadata(req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "", durationMs));
        return r;
    }

    private Map<String, Object> metadata(String correlationId, long durationMs) {
        return Map.of("correlationId", correlationId != null ? correlationId : "",
                      "processedBy", PROCESSED_BY, "durationMs", durationMs);
    }

    private void send(String queue, Object payload) {
        sqsTemplate.send(to -> to.queue(queue).payload(payload));
    }
}
