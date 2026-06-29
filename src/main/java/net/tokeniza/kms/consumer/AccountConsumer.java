package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.AccountCreateRequestDto;
import net.tokeniza.kms.service.AccountService;
import net.tokeniza.kms.service.GasFundService;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final AccountService accountService;
    private final GasFundService gasFundService;
    private final SqsTemplate sqsTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @SqsListener("${kms.sqs.account-create-request}")
    public void onMessage(Message<String> message) {
        long startMs = System.currentTimeMillis();
        AccountCreateRequestDto req = null;
        try {
            req = objectMapper.readValue(message.getPayload(), AccountCreateRequestDto.class);
            log.info("Account create: idempotencyKey={} userId={} network={}",
                    req.getIdempotencyKey(), req.getUserId(), req.getNetwork());

            AccountService.WalletResult wallet = accountService.createWallet(req.getUserId());
            gasFundService.fundAsync(wallet.address());

            send(props.getSqs().getAccountCreateResponse(), successPayload(req, wallet, System.currentTimeMillis() - startMs));
            log.info("Account created: keyId={} address={}", wallet.keyId(), wallet.address());

        } catch (Exception e) {
            log.error("Account create failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                send(props.getSqs().getAccountCreateResponse(), errorPayload(req, e.getMessage(), System.currentTimeMillis() - startMs));
            }
        }
    }

    private Map<String, Object> successPayload(AccountCreateRequestDto req,
                                               AccountService.WalletResult wallet, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "account.create.succeeded");
        r.put("userId", req.getUserId());
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("wallet", Map.of("id", wallet.keyId(), "network", req.getNetwork(), "address", wallet.address()));
        r.put("timestamp", Instant.now().toString());
        r.put("metadata", Map.of("processedBy", PROCESSED_BY, "durationMs", durationMs));
        return r;
    }

    private Map<String, Object> errorPayload(AccountCreateRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> r = new HashMap<>();
        r.put("event", "account.create.failed");
        r.put("userId", req.getUserId());
        r.put("idempotencyKey", req.getIdempotencyKey());
        r.put("error", Map.of("code", "EXECUTION_FAILED", "message", errorMessage));
        r.put("timestamp", Instant.now().toString());
        r.put("metadata", Map.of("processedBy", PROCESSED_BY, "durationMs", durationMs));
        return r;
    }

    private void send(String queue, Object payload) {
        sqsTemplate.send(to -> to.queue(queue).payload(payload));
    }
}
