package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.TokenEventRequestDto;
import net.tokeniza.kms.service.TokenEventService;
import org.springframework.messaging.Message;
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
    private final SqsTemplate sqsTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @SqsListener("${kms.sqs.token-event-request}")
    public void onMessage(Message<String> message) {
        long startMs = System.currentTimeMillis();
        TokenEventRequestDto req = null;
        try {
            req = objectMapper.readValue(message.getPayload(), TokenEventRequestDto.class);
            log.info("Token event: idempotencyKey={} op={} contract={}",
                    req.getIdempotencyKey(), req.getOperation().getType(), req.getToken().getAddress());

            TokenEventService.EventResult result = tokenEventService.executeOperation(req);

            send(props.getSqs().getTokenEventResponse(), successPayload(req, result, System.currentTimeMillis() - startMs));
            log.info("Token event succeeded: op={} txHash={}", req.getOperation().getType(), result.txHash());

        } catch (Exception e) {
            log.error("Token event failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                send(props.getSqs().getTokenEventResponse(), errorPayload(req, e.getMessage(), System.currentTimeMillis() - startMs));
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

    private void send(String queue, Object payload) {
        sqsTemplate.send(to -> to.queue(queue).payload(payload));
    }
}
