package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.dto.BalanceRequestDto;
import net.tokeniza.kms.persistence.RequestLogService;
import net.tokeniza.kms.service.BalanceService;
import net.tokeniza.kms.service.ResponsePublisher;
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
    private final ResponsePublisher responsePublisher;
    private final RequestLogService requestLogService;
    private final ObjectMapper objectMapper;

    void handle(String body) {
        long startMs = System.currentTimeMillis();
        BalanceRequestDto req = null;
        try {
            req = objectMapper.readValue(body, BalanceRequestDto.class);
            requestLogService.logReceived(req.getIdempotencyKey(), "BALANCE_QUERY", req.getResponseQueue(), body);

            log.info("Balance query: idempotencyKey={} contract={} wallet={}",
                    req.getIdempotencyKey(), req.getToken().getAddress(), req.getWallet().getAddress());

            BalanceService.BalanceResult balance = balanceService.getErc20Balance(
                    req.getToken().getAddress(), req.getWallet().getAddress());

            Map<String, Object> okPayload = successPayload(req, balance, System.currentTimeMillis() - startMs);
            responsePublisher.publish(req.getResponseQueue(), req.getIdempotencyKey(), okPayload);
            requestLogService.markCompleted(req.getIdempotencyKey(), okPayload);

        } catch (Exception e) {
            log.error("Balance query failed: {}", e.getMessage(), e);
            if (req != null) {
                Map<String, Object> errPayload = errorPayload(req, e.getMessage(), System.currentTimeMillis() - startMs);
                responsePublisher.publish(req.getResponseQueue(), req.getIdempotencyKey(), errPayload);
                requestLogService.markFailed(req.getIdempotencyKey(), e.getMessage());
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
}
