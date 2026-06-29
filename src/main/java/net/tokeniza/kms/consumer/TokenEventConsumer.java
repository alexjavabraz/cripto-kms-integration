package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.TokenEventRequestDto;
import net.tokeniza.kms.persistence.RequestLogService;
import net.tokeniza.kms.service.ResponsePublisher;
import net.tokeniza.kms.service.TokenEventService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenEventConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final TokenEventService tokenEventService;
    private final ResponsePublisher responsePublisher;
    private final RequestLogService requestLogService;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    void handle(String body) {
        long startMs = System.currentTimeMillis();
        TokenEventRequestDto req = null;
        try {
            req = objectMapper.readValue(body, TokenEventRequestDto.class);
            requestLogService.logReceived(req.getIdempotencyKey(), "TOKEN_EVENT", req.getResponseQueue(), body);

            log.info("Token event: idempotencyKey={} op={} contract={}",
                    req.getIdempotencyKey(), req.getOperation().getType(), req.getToken().getAddress());

            TokenEventService.EventResult result = tokenEventService.executeOperation(req);

            Map<String, Object> okPayload = successPayload(req, result, System.currentTimeMillis() - startMs);
            responsePublisher.publish(req.getResponseQueue(), req.getIdempotencyKey(), okPayload);
            requestLogService.markCompleted(req.getIdempotencyKey(), okPayload);
            log.info("Token event succeeded: op={} txHash={}", req.getOperation().getType(), result.txHash());

        } catch (Exception e) {
            log.error("Token event failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                Map<String, Object> errPayload = errorPayload(req, e.getMessage(), System.currentTimeMillis() - startMs);
                responsePublisher.publish(req.getResponseQueue(), req.getIdempotencyKey(), errPayload);
                requestLogService.markFailed(req.getIdempotencyKey(), e.getMessage());
            }
        }
    }

    private Map<String, Object> successPayload(TokenEventRequestDto req,
                                               TokenEventService.EventResult result, long durationMs) {
        String explorerUrl = props.getDlt().getExplorerUrl();
        Map<String, Object> r = new HashMap<>();
        r.put("event", "token.event.succeeded");
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("timestamp", Instant.now().toString());
        r.put("network", Map.of("name", req.getNetwork().getName(), "chainId", req.getNetwork().getChainId()));
        r.put("token", Map.of("address", req.getToken().getAddress(), "standard", req.getToken().getStandard()));
        r.put("operation", Map.of("type", req.getOperation().getType()));
        r.put("result", Map.of("txHash", result.txHash(), "blockNumber", result.blockNumber(), "gasUsed", result.gasUsed()));
        if (!explorerUrl.isBlank()) {
            r.put("explorer", Map.of("transactionUrl", explorerUrl + "/tx/" + result.txHash()));
        }
        r.put("metadata", metadata(req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "", durationMs));
        return r;
    }

    private Map<String, Object> errorPayload(TokenEventRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "token.event.failed");
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("timestamp", Instant.now().toString());
        r.put("network", Map.of("name", req.getNetwork() != null ? req.getNetwork().getName() : "unknown", "chainId", 0));
        r.put("token", Map.of("address", req.getToken() != null ? req.getToken().getAddress() : "", "standard", "ERC20"));
        r.put("operation", Map.of("type", req.getOperation() != null ? req.getOperation().getType() : "unknown"));
        r.put("error", Map.of("code", "EXECUTION_FAILED", "message", errorMessage));
        r.put("metadata", metadata(req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "", durationMs));
        return r;
    }

    private Map<String, Object> metadata(String correlationId, long durationMs) {
        return Map.of("correlationId", correlationId != null ? correlationId : "",
                      "processedBy", PROCESSED_BY, "durationMs", durationMs);
    }
}
