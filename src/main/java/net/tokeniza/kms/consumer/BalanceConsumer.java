package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.BalanceRequestDto;
import net.tokeniza.kms.service.BalanceService;
import org.springframework.messaging.Message;
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
    private final SqsTemplate sqsTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @SqsListener("${kms.sqs.balance-request}")
    public void onMessage(Message<String> message) {
        long startMs = System.currentTimeMillis();
        BalanceRequestDto req = null;
        try {
            req = objectMapper.readValue(message.getPayload(), BalanceRequestDto.class);
            log.info("Balance query: idempotencyKey={} contract={} wallet={}",
                    req.getIdempotencyKey(), req.getToken().getAddress(), req.getWallet().getAddress());

            BalanceService.BalanceResult balance = balanceService.getErc20Balance(
                    req.getToken().getAddress(), req.getWallet().getAddress());

            send(props.getSqs().getBalanceResponse(), successPayload(req, balance, System.currentTimeMillis() - startMs));

        } catch (Exception e) {
            log.error("Balance query failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                send(props.getSqs().getBalanceResponse(), errorPayload(req, e.getMessage(), System.currentTimeMillis() - startMs));
            }
        }
    }

    private Map<String, Object> successPayload(BalanceRequestDto req,
                                               BalanceService.BalanceResult balance, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "token.balance.responded");
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("timestamp", Instant.now().toString());
        r.put("network", Map.of("name", req.getNetwork().getName(), "chainId", req.getNetwork().getChainId()));
        r.put("token", Map.of("address", req.getToken().getAddress(),
                "name", balance.name(), "symbol", balance.symbol(), "decimals", balance.decimals()));
        r.put("wallet", Map.of("address", req.getWallet().getAddress()));
        r.put("balance", Map.of("raw", balance.raw(), "formatted", balance.formatted()));
        r.put("metadata", metadata(req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "", durationMs));
        return r;
    }

    private Map<String, Object> errorPayload(BalanceRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "token.balance.failed");
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("timestamp", Instant.now().toString());
        r.put("network", Map.of("name", req.getNetwork() != null ? req.getNetwork().getName() : "unknown", "chainId", 0));
        r.put("error", Map.of("code", "QUERY_FAILED", "message", errorMessage));
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
