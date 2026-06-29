package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.BalanceRequestDto;
import net.tokeniza.kms.service.BalanceService;
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
public class BalanceConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final BalanceService balanceService;
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${kms.queue.balance}")
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        long startMs = System.currentTimeMillis();
        BalanceRequestDto req = null;

        try {
            req = objectMapper.readValue(message.getBody(), BalanceRequestDto.class);
            log.info("Balance query: idempotencyKey={} contract={} wallet={}",
                    req.getIdempotencyKey(), req.getToken().getAddress(), req.getWallet().getAddress());

            BalanceService.BalanceResult balance = balanceService.getErc20Balance(
                    req.getToken().getAddress(),
                    req.getWallet().getAddress()
            );

            publishSuccess(req, balance, System.currentTimeMillis() - startMs);

        } catch (Exception e) {
            log.error("Balance query failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                publishError(req, e.getMessage(), System.currentTimeMillis() - startMs);
            }
        } finally {
            channel.basicAck(tag, false);
        }
    }

    private void publishSuccess(BalanceRequestDto req, BalanceService.BalanceResult balance, long durationMs) {
        Map<String, Object> response = new HashMap<>();
        response.put("event", "token.balance.responded");
        response.put("idempotencyKey", req.getIdempotencyKey());
        response.put("timestamp", Instant.now().toString());
        response.put("network", Map.of("name", req.getNetwork().getName(), "chainId", req.getNetwork().getChainId()));
        response.put("token", Map.of(
                "address", req.getToken().getAddress(),
                "name", balance.name(),
                "symbol", balance.symbol(),
                "decimals", balance.decimals()
        ));
        response.put("wallet", Map.of("address", req.getWallet().getAddress()));
        response.put("balance", Map.of("raw", balance.raw(), "formatted", balance.formatted()));
        response.put("metadata", Map.of(
                "correlationId", req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY,
                "durationMs", durationMs
        ));

        rabbitTemplate.convertAndSend(props.getExchange().getBalanceResponse(), "token.balance.responded", response);
    }

    private void publishError(BalanceRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> response = new HashMap<>();
        response.put("event", "token.balance.failed");
        response.put("idempotencyKey", req.getIdempotencyKey());
        response.put("timestamp", Instant.now().toString());
        response.put("network", Map.of("name", req.getNetwork() != null ? req.getNetwork().getName() : "unknown", "chainId", 0));
        response.put("error", Map.of("code", "QUERY_FAILED", "message", errorMessage));
        response.put("metadata", Map.of(
                "correlationId", req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY,
                "durationMs", durationMs
        ));

        rabbitTemplate.convertAndSend(props.getExchange().getBalanceResponse(), "token.balance.failed", response);
    }
}
