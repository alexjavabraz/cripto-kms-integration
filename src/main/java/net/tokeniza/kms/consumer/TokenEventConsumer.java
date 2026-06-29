package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.TokenEventRequestDto;
import net.tokeniza.kms.service.TokenEventService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${kms.queue.token-event}")
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        long startMs = System.currentTimeMillis();
        TokenEventRequestDto req = null;

        try {
            req = objectMapper.readValue(message.getBody(), TokenEventRequestDto.class);
            log.info("Token event: idempotencyKey={} op={} contract={}",
                    req.getIdempotencyKey(), req.getOperation().getType(), req.getToken().getAddress());

            TokenEventService.EventResult result = tokenEventService.executeOperation(req);

            publishSuccess(req, result, System.currentTimeMillis() - startMs);
            log.info("Token event succeeded: op={} txHash={}", req.getOperation().getType(), result.txHash());

        } catch (Exception e) {
            log.error("Token event failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                publishError(req, e.getMessage(), System.currentTimeMillis() - startMs);
            }
        } finally {
            channel.basicAck(tag, false);
        }
    }

    private void publishSuccess(TokenEventRequestDto req,
                                TokenEventService.EventResult result, long durationMs) {
        String explorerUrl = props.getDlt().getExplorerUrl();
        Map<String, Object> response = new HashMap<>();
        response.put("event", "token.event.succeeded");
        response.put("idempotencyKey", req.getIdempotencyKey());
        response.put("timestamp", Instant.now().toString());
        response.put("network", Map.of("name", req.getNetwork().getName(), "chainId", req.getNetwork().getChainId()));
        response.put("token", Map.of("address", req.getToken().getAddress(), "standard", req.getToken().getStandard()));
        response.put("operation", Map.of("type", req.getOperation().getType()));
        response.put("result", Map.of("txHash", result.txHash(), "blockNumber", result.blockNumber(), "gasUsed", result.gasUsed()));
        if (!explorerUrl.isBlank()) {
            response.put("explorer", Map.of("transactionUrl", explorerUrl + "/tx/" + result.txHash()));
        }
        response.put("metadata", Map.of(
                "correlationId", req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY,
                "durationMs", durationMs
        ));

        rabbitTemplate.convertAndSend(props.getExchange().getTokenEventResponse(), "token.event.succeeded", response);
    }

    private void publishError(TokenEventRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> response = new HashMap<>();
        response.put("event", "token.event.failed");
        response.put("idempotencyKey", req.getIdempotencyKey());
        response.put("timestamp", Instant.now().toString());
        response.put("network", Map.of("name", req.getNetwork() != null ? req.getNetwork().getName() : "unknown", "chainId", 0));
        response.put("token", Map.of("address", req.getToken() != null ? req.getToken().getAddress() : "", "standard", "ERC20"));
        response.put("operation", Map.of("type", req.getOperation() != null ? req.getOperation().getType() : "unknown"));
        response.put("error", Map.of("code", "EXECUTION_FAILED", "message", errorMessage));
        response.put("metadata", Map.of(
                "correlationId", req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY,
                "durationMs", durationMs
        ));

        rabbitTemplate.convertAndSend(props.getExchange().getTokenEventResponse(), "token.event.failed", response);
    }
}
