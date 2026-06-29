package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import net.tokeniza.kms.dto.TokenTransferRequestDto;
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
public class TransferConsumer {

    private static final String PROCESSED_BY = "kms-integration";

    private final TokenTransferService transferService;
    private final RabbitTemplate rabbitTemplate;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${kms.queue.token-transfer}")
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        long startMs = System.currentTimeMillis();
        TokenTransferRequestDto req = null;

        try {
            req = objectMapper.readValue(message.getBody(), TokenTransferRequestDto.class);
            log.info("Processing token transfer: idempotencyKey={} network={} to={} amount={}",
                    req.getIdempotencyKey(), req.getNetwork(),
                    req.getTransfer().getToAddress(), req.getTransfer().getAmount());

            TokenTransferService.TransferResult result = transferService.executeTransfer(
                    req.getToken().getContractAddress(),
                    req.getTransfer().getToAddress(),
                    req.getTransfer().getAmount(),
                    req.getToken().getDecimals()
            );

            publishSuccess(req, result, System.currentTimeMillis() - startMs);
            log.info("Token transfer succeeded: idempotencyKey={} txHash={}",
                    req.getIdempotencyKey(), result.txHash());

        } catch (Exception e) {
            log.error("Token transfer failed: {}", e.getMessage(), e);
            Sentry.captureException(e);
            if (req != null) {
                publishError(req, e.getMessage(), System.currentTimeMillis() - startMs);
            }
        } finally {
            channel.basicAck(tag, false);
        }
    }

    private void publishSuccess(TokenTransferRequestDto req,
                                TokenTransferService.TransferResult result, long durationMs) {
        Map<String, Object> response = new HashMap<>();
        response.put("event", "token.transfer.succeeded");
        response.put("idempotencyKey", req.getIdempotencyKey());
        response.put("timestamp", Instant.now().toString());
        response.put("network", req.getNetwork());
        response.put("requester", Map.of("userId", req.getRequester().getUserId()));
        response.put("token", Map.of(
                "contractAddress", req.getToken().getContractAddress(),
                "decimals", req.getToken().getDecimals()
        ));
        response.put("transfer", Map.of(
                "toAddress", req.getTransfer().getToAddress(),
                "amount", req.getTransfer().getAmount()
        ));
        response.put("result", Map.of(
                "txHash", result.txHash(),
                "blockNumber", result.blockNumber(),
                "gasUsed", result.gasUsed()
        ));
        response.put("metadata", Map.of(
                "correlationId", req.getMetadata() != null ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY,
                "durationMs", durationMs
        ));

        rabbitTemplate.convertAndSend(
                props.getExchange().getTokenTransferResponse(),
                "token.transfer.succeeded",
                response
        );
    }

    private void publishError(TokenTransferRequestDto req, String errorMessage, long durationMs) {
        Map<String, Object> response = new HashMap<>();
        response.put("event", "token.transfer.failed");
        response.put("idempotencyKey", req.getIdempotencyKey());
        response.put("timestamp", Instant.now().toString());
        response.put("network", req.getNetwork());
        response.put("requester", Map.of("userId", req.getRequester() != null ? req.getRequester().getUserId() : "unknown"));
        response.put("token", Map.of(
                "contractAddress", req.getToken() != null ? req.getToken().getContractAddress() : "0x0000000000000000000000000000000000000000",
                "decimals", 18
        ));
        response.put("transfer", Map.of(
                "toAddress", req.getTransfer() != null ? req.getTransfer().getToAddress() : "0x0000000000000000000000000000000000000000",
                "amount", "0"
        ));
        response.put("error", Map.of("code", "EXECUTION_FAILED", "message", errorMessage));
        response.put("metadata", Map.of(
                "correlationId", req.getMetadata() != null && req.getMetadata().getCorrelationId() != null
                        ? req.getMetadata().getCorrelationId() : "",
                "processedBy", PROCESSED_BY,
                "durationMs", durationMs
        ));

        rabbitTemplate.convertAndSend(
                props.getExchange().getTokenTransferResponse(),
                "token.transfer.failed",
                response
        );
    }
}
