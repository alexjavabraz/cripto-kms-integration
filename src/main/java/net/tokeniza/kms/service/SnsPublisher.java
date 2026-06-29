package net.tokeniza.kms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.config.AppProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnsPublisher {

    private final SnsClient snsClient;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    /**
     * Publishes a response to the FIFO SNS topic.
     *
     * @param messageGroupId  groups messages for ordering — use the request's idempotencyKey or requestId
     * @param payload         response object; serialised to JSON
     */
    public void publish(String messageGroupId, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            snsClient.publish(PublishRequest.builder()
                    .topicArn(props.getSns().getTopicArn())
                    .message(json)
                    .messageGroupId(messageGroupId)
                    .messageDeduplicationId(UUID.randomUUID().toString())
                    .build());
            log.debug("SNS published: groupId={} topic={}", messageGroupId, props.getSns().getTopicArn());
        } catch (Exception e) {
            log.error("SNS publish failed: groupId={} error={}", messageGroupId, e.getMessage(), e);
            throw new RuntimeException("SNS publish failed", e);
        }
    }
}
