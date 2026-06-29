package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.UserTransferRequestDto;
import net.tokeniza.kms.service.TokenTransferService;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserTransferConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final TokenTransferService transferService;
    private final SqsTemplate sqsTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @SqsListener("${kms.sqs.user-transfer-request}")
    public void onMessage(Message<String> message) {
        long startMs = System.currentTimeMillis();
        UserTransferRequestDto req = null;
        try {
            req = objectMapper.readValue(message.getPayload(), UserTransferRequestDto.class);
            log.info("User transfer: requestId={} userId={} to={} amount={}",
                    req.getRequestId(), req.getUserId(), req.getToAddress(), req.getAmount());

            TokenTransferService.TransferResult result = transferService.executeUserTransfer(
                    req.getUserWalletId(),
                    req.getContractAddress(),
                    req.getToAddress(),
                    req.getAmount(),
                    req.getDecimals()
            );

            send(props.getSqs().getUserTransferResponse(), successPayload(req, result, System.currentTimeMillis() - startMs));
            log.info("User transfer succeeded: requestId={} txHash={}", req.getRequestId(), result.txHash());

        } catch (Exception e) {
            log.error("User transfer failed: requestId={} error={}", req != null ? req.getRequestId() : "?", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                send(props.getSqs().getUserTransferResponse(), errorPayload(req, e.getMessage(), System.currentTimeMillis() - startMs));
            }
        }
    }

    private Map<String, Object> successPayload(UserTransferRequestDto req,
                                               TokenTransferService.TransferResult result, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "user.transfer.completed");
        r.put("requestId", req.getRequestId());
        r.put("userId", req.getUserId());
        r.put("result", Map.of("txHash", result.txHash(), "blockNumber", result.blockNumber(), "gasUsed", result.gasUsed()));
        r.put("metadata", metadata(req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "", durationMs));
        return r;
    }

    private Map<String, Object> errorPayload(UserTransferRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "user.transfer.failed");
        r.put("requestId", req.getRequestId());
        r.put("userId", req.getUserId());
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
