package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.dto.AccountCreateRequestDto;
import net.tokeniza.kms.persistence.RequestLogService;
import net.tokeniza.kms.persistence.Wallet;
import net.tokeniza.kms.persistence.WalletService;
import net.tokeniza.kms.service.AccountService;
import net.tokeniza.kms.service.GasFundService;
import net.tokeniza.kms.service.ResponsePublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final AccountService accountService;
    private final GasFundService gasFundService;
    private final WalletService walletService;
    private final ResponsePublisher responsePublisher;
    private final RequestLogService requestLogService;
    private final ObjectMapper objectMapper;

    void handle(String body) {
        long startMs = System.currentTimeMillis();
        AccountCreateRequestDto req = null;
        try {
            req = objectMapper.readValue(body, AccountCreateRequestDto.class);
            requestLogService.logReceived(req.getIdempotencyKey(), "ACCOUNT_CREATE", req.getResponseQueue(), body);

            log.info("Account create: idempotencyKey={} clientId={}", req.getIdempotencyKey(), req.getClientId());

            Optional<Wallet> existing = walletService.findByClientId(req.getClientId());
            if (existing.isPresent()) {
                log.info("Account already exists for clientId={} — returning existing address", req.getClientId());
                Map<String, Object> okPayload = successPayload(req, existing.get().getAddress(), System.currentTimeMillis() - startMs);
                responsePublisher.publish(req.getResponseQueue(), req.getIdempotencyKey(), okPayload);
                requestLogService.markCompleted(req.getIdempotencyKey(), okPayload);
                return;
            }

            AccountService.WalletResult wallet = accountService.createWallet(req.getClientId());
            gasFundService.fundAsync(wallet.address());

            Map<String, Object> okPayload = successPayload(req, wallet.address(), System.currentTimeMillis() - startMs);
            responsePublisher.publish(req.getResponseQueue(), req.getIdempotencyKey(), okPayload);
            requestLogService.markCompleted(req.getIdempotencyKey(), okPayload);
            log.info("Account created: clientId={} address={}", req.getClientId(), wallet.address());

        } catch (Exception e) {
            log.error("Account create failed: {}", e.getMessage(), e);
            if (req != null) {
                Map<String, Object> errPayload = errorPayload(req, e.getMessage(), System.currentTimeMillis() - startMs);
                responsePublisher.publish(req.getResponseQueue(), req.getIdempotencyKey(), errPayload);
                requestLogService.markFailed(req.getIdempotencyKey(), e.getMessage());
            }
        }
    }

    private Map<String, Object> successPayload(AccountCreateRequestDto req, String address, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "account.create.succeeded");
        r.put("clientId", req.getClientId());
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("wallet", Map.of("address", address));
        r.put("timestamp", Instant.now().toString());
        r.put("metadata", Map.of("processedBy", PROCESSED_BY, "durationMs", durationMs));
        return r;
    }

    private Map<String, Object> errorPayload(AccountCreateRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "account.create.failed");
        r.put("clientId", req.getClientId());
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("error", Map.of("code", "EXECUTION_FAILED", "message", errorMessage));
        r.put("timestamp", Instant.now().toString());
        r.put("metadata", Map.of("processedBy", PROCESSED_BY, "durationMs", durationMs));
        return r;
    }
}
