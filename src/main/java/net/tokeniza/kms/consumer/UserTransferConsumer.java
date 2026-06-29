package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.dto.UserTransferRequestDto;
import net.tokeniza.kms.persistence.RequestLogService;
import net.tokeniza.kms.service.ResponsePublisher;
import net.tokeniza.kms.service.TokenTransferService;
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
    private final ResponsePublisher responsePublisher;
    private final RequestLogService requestLogService;
    private final ObjectMapper objectMapper;

    void handle(String body) {
        long startMs = System.currentTimeMillis();
        UserTransferRequestDto req = null;
        try {
            req = objectMapper.readValue(body, UserTransferRequestDto.class);
            requestLogService.logReceived(req.getRequestId(), "USER_TRANSFER", req.getResponseQueue(), body);

            log.info("User transfer: requestId={} userId={} to={} amount={}",
                    req.getRequestId(), req.getUserId(), req.getToAddress(), req.getAmount());

            TokenTransferService.TransferResult result = transferService.executeUserTransfer(
                    req.getUserWalletId(),
                    req.getContractAddress(),
                    req.getToAddress(),
                    req.getAmount(),
                    req.getDecimals()
            );

            Map<String, Object> okPayload = successPayload(req, result, System.currentTimeMillis() - startMs);
            responsePublisher.publish(req.getResponseQueue(), req.getRequestId(), okPayload);
            requestLogService.markCompleted(req.getRequestId(), okPayload);
            log.info("User transfer succeeded: requestId={} txHash={}", req.getRequestId(), result.txHash());

        } catch (Exception e) {
            log.error("User transfer failed: requestId={} error={}", req != null ? req.getRequestId() : "?", e.getMessage(), e);
            if (req != null) {
                Map<String, Object> errPayload = errorPayload(req, e.getMessage(), System.currentTimeMillis() - startMs);
                responsePublisher.publish(req.getResponseQueue(), req.getRequestId(), errPayload);
                requestLogService.markFailed(req.getRequestId(), e.getMessage());
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
}
