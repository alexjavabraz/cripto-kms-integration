package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.dto.TokenTransferRequestDto;
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
public class TransferConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final TokenTransferService transferService;
    private final ResponsePublisher responsePublisher;
    private final RequestLogService requestLogService;
    private final ObjectMapper objectMapper;

    void handle(String body) {
        long startMs = System.currentTimeMillis();
        TokenTransferRequestDto req = null;
        try {
            req = objectMapper.readValue(body, TokenTransferRequestDto.class);
            requestLogService.logReceived(req.getIdempotencyKey(), "TOKEN_TRANSFER", req.getResponseQueue(), body);

            log.info("Token transfer: idempotencyKey={} network={} to={} amount={}",
                    req.getIdempotencyKey(), req.getNetwork(),
                    req.getTransfer().getToAddress(), req.getTransfer().getAmount());

            TokenTransferService.TransferResult result = transferService.executeTransfer(
                    req.getToken().getContractAddress(),
                    req.getTransfer().getToAddress(),
                    req.getTransfer().getAmount(),
                    req.getToken().getDecimals()
            );

            Map<String, Object> okPayload = successPayload(req, result, System.currentTimeMillis() - startMs);
            responsePublisher.publish(req.getResponseQueue(), req.getIdempotencyKey(), okPayload);
            requestLogService.markCompleted(req.getIdempotencyKey(), okPayload);
            log.info("Token transfer succeeded: idempotencyKey={} txHash={}", req.getIdempotencyKey(), result.txHash());

        } catch (Exception e) {
            log.error("Token transfer failed: {}", e.getMessage(), e);
            if (req != null) {
                Map<String, Object> errPayload = errorPayload(req, e.getMessage(), System.currentTimeMillis() - startMs);
                responsePublisher.publish(req.getResponseQueue(), req.getIdempotencyKey(), errPayload);
                requestLogService.markFailed(req.getIdempotencyKey(), e.getMessage());
            }
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
}
