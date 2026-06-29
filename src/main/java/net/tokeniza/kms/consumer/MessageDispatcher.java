package net.tokeniza.kms.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Single entry point for all incoming messages.
 *
 * The client's BFF publishes to an SNS topic; SNS delivers to the FIFO SQS queue.
 * This dispatcher unwraps the SNS notification envelope, reads the "type"
 * MessageAttribute set by the BFF, and routes to the appropriate consumer handler.
 *
 * Expected SNS MessageAttribute: { "type": { "Type": "String", "Value": "TOKEN_TRANSFER" } }
 * Supported type values: TOKEN_CREATION, TOKEN_TRANSFER, USER_TRANSFER,
 *                        BALANCE_QUERY, TOKEN_EVENT, ACCOUNT_CREATE
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageDispatcher {

    private final CreationConsumer    creationConsumer;
    private final TransferConsumer    transferConsumer;
    private final UserTransferConsumer userTransferConsumer;
    private final BalanceConsumer     balanceConsumer;
    private final TokenEventConsumer  tokenEventConsumer;
    private final AccountConsumer     accountConsumer;
    private final ObjectMapper        objectMapper;

    @SqsListener("${kms.sqs.queue-name}")
    public void dispatch(Message<String> message) {
        String rawBody = message.getPayload();
        try {
            String type    = extractType(rawBody);
            String payload = extractPayload(rawBody);

            log.info("Dispatching message: type={}", type);

            switch (type) {
                case "TOKEN_CREATION" -> creationConsumer.handle(payload);
                case "TOKEN_TRANSFER" -> transferConsumer.handle(payload);
                case "USER_TRANSFER"  -> userTransferConsumer.handle(payload);
                case "BALANCE_QUERY"  -> balanceConsumer.handle(payload);
                case "TOKEN_EVENT"    -> tokenEventConsumer.handle(payload);
                case "ACCOUNT_CREATE" -> accountConsumer.handle(payload);
                default -> log.warn("Unknown message type '{}' — message discarded", type);
            }
        } catch (Exception e) {
            log.error("Dispatch error: {}", e.getMessage(), e);
            // Do not re-throw — message is always deleted from SQS (never nack)
        }
    }

    // ── SNS envelope handling ─────────────────────────────────────────────────

    /**
     * Extracts the routing type.
     * Reads "type" MessageAttribute from the SNS envelope; falls back to body's "type" field.
     */
    private String extractType(String rawBody) {
        try {
            SnsEnvelope envelope = objectMapper.readValue(rawBody, SnsEnvelope.class);
            if ("Notification".equals(envelope.getType()) && envelope.getMessageAttributes() != null) {
                SnsEnvelope.Attribute attr = envelope.getMessageAttributes().get("type");
                if (attr != null && attr.getValue() != null) {
                    return attr.getValue().toUpperCase();
                }
            }
        } catch (Exception ignored) {}
        // Fallback: read "type" from the raw body itself (useful for direct SQS puts in tests)
        try {
            return objectMapper.readTree(rawBody).path("type").asText("UNKNOWN").toUpperCase();
        } catch (Exception ignored) {}
        return "UNKNOWN";
    }

    /**
     * Extracts the actual message payload.
     * If the body is an SNS envelope, returns the inner "Message" string.
     * Otherwise returns the raw body as-is.
     */
    private String extractPayload(String rawBody) {
        try {
            SnsEnvelope envelope = objectMapper.readValue(rawBody, SnsEnvelope.class);
            if ("Notification".equals(envelope.getType()) && envelope.getMessage() != null) {
                return envelope.getMessage();
            }
        } catch (Exception ignored) {}
        return rawBody;
    }

    // ── SNS notification envelope DTO ─────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SnsEnvelope {
        @JsonProperty("Type")    private String type;
        @JsonProperty("Message") private String message;
        @JsonProperty("MessageAttributes") private Map<String, Attribute> messageAttributes;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Attribute {
            @JsonProperty("Type")  private String type;
            @JsonProperty("Value") private String value;
        }
    }
}
