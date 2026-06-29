package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.UserTransferRequestDto;
import net.tokeniza.kms.service.TokenTransferService;
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
public class UserTransferConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final TokenTransferService transferService;
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${kms.queue.user-transfer}")
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        long startMs = System.currentTimeMillis();
        UserTransferRequestDto req = null;

        try {
            req = objectMapper.readValue(message.getBody(), UserTransferRequestDto.class);
            log.info("Processing user transfer: requestId={} userId={} to={} amount={}",
                    req.getRequestId(), req.getUserId(), req.getToAddress(), req.getAmount());

            TokenTransferService.TransferResult result = transferService.executeUserTransfer(
                    req.getUserWalletId(),
                    req.getContractAddress(),
                    req.getToAddress(),
                    req.getAmount(),
                    req.getDecimals()
            );

            publishSuccess(req, result, System.currentTimeMillis() - startMs);
            log.info("User transfer succeeded: requestId={} txHash={}", req.getRequestId(), result.txHash());

        } catch (Exception e) {
            log.error("User transfer failed: requestId={} error={}", req != null ? req.getRequestId() : "?", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                publishError(req, e.getMessage(), System.currentTimeMillis() - startMs);
            }
        } finally {
            channel.basicAck(tag, false);
        }
    }

    private void publishSuccess(UserTransferRequestDto req,
                                TokenTransferService.TransferResult result, long durationMs) {
        Map<String, Object> response = new HashMap<>();
        response.put("event", "user.transfer.completed");
        response.put("requestId", req.getRequestId());
        response.put("userId", req.getUserId());
        response.put("result", Map.of(
                "txHash", result.txHash(),
                "blockNumber", result.blockNumber(),
                "gasUsed", result.gasUsed()
        ));
        response.put("metadata", Map.of(
                "correlationId", req.getMetadata() != null && req.getMetadata().getCorrelationId() != null
                        ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY,
                "durationMs", durationMs
        ));

        rabbitTemplate.convertAndSend(
                props.getExchange().getUserTransferResponse(),
                "user.transfer.completed",
                response
        );
    }

    private void publishError(UserTransferRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> response = new HashMap<>();
        response.put("event", "user.transfer.failed");
        response.put("requestId", req.getRequestId());
        response.put("userId", req.getUserId());
        response.put("error", Map.of("code", "EXECUTION_FAILED", "message", errorMessage));
        response.put("metadata", Map.of(
                "correlationId", req.getMetadata() != null && req.getMetadata().getCorrelationId() != null
                        ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY,
                "durationMs", durationMs
        ));

        rabbitTemplate.convertAndSend(
                props.getExchange().getUserTransferResponse(),
                "user.transfer.failed",
                response
        );
    }
}
