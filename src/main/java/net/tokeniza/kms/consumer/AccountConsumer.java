package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.AccountCreateRequestDto;
import net.tokeniza.kms.service.AccountService;
import net.tokeniza.kms.service.GasFundService;
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
public class AccountConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final AccountService accountService;
    private final GasFundService gasFundService;
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${kms.queue.account-create}")
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        long startMs = System.currentTimeMillis();
        AccountCreateRequestDto req = null;

        try {
            req = objectMapper.readValue(message.getBody(), AccountCreateRequestDto.class);
            log.info("Creating account: idempotencyKey={} userId={} network={}",
                    req.getIdempotencyKey(), req.getUserId(), req.getNetwork());

            AccountService.WalletResult wallet = accountService.createWallet(req.getUserId());

            // Best-effort gas funding — does not block ack
            gasFundService.fundAsync(wallet.address());

            publishSuccess(req, wallet, System.currentTimeMillis() - startMs);
            log.info("Account created: keyId={} address={}", wallet.keyId(), wallet.address());

        } catch (Exception e) {
            log.error("Account creation failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                publishError(req, e.getMessage(), System.currentTimeMillis() - startMs);
            }
        } finally {
            channel.basicAck(tag, false);
        }
    }

    private void publishSuccess(AccountCreateRequestDto req,
                                AccountService.WalletResult wallet, long durationMs) {
        Map<String, Object> response = new HashMap<>();
        response.put("event", "account.create.succeeded");
        response.put("userId", req.getUserId());
        response.put("idempotencyKey", req.getIdempotencyKey());
        response.put("wallet", Map.of(
                "id", wallet.keyId(),
                "network", req.getNetwork(),
                "address", wallet.address()
        ));
        response.put("timestamp", Instant.now().toString());
        response.put("metadata", Map.of("processedBy", PROCESSED_BY, "durationMs", durationMs));

        rabbitTemplate.convertAndSend(
                props.getExchange().getAccountCreateResponse(),
                "account.create.succeeded",
                response
        );
    }

    private void publishError(AccountCreateRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> response = new HashMap<>();
        response.put("event", "account.create.failed");
        response.put("userId", req.getUserId());
        response.put("idempotencyKey", req.getIdempotencyKey());
        response.put("error", Map.of("code", "EXECUTION_FAILED", "message", errorMessage));
        response.put("timestamp", Instant.now().toString());
        response.put("metadata", Map.of("processedBy", PROCESSED_BY, "durationMs", durationMs));

        rabbitTemplate.convertAndSend(
                props.getExchange().getAccountCreateResponse(),
                "account.create.failed",
                response
        );
    }
}
