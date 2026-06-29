package net.tokeniza.kms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Routes responses to the caller's SQS queue (when responseQueue is set) or
 * to the default outbound SNS topic otherwise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResponsePublisher {

    private final SqsTemplate sqsTemplate;
    private final SnsPublisher snsPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Publishes the response payload.
     *
     * @param responseQueue  SQS queue name or URL provided by the caller; if blank falls back to SNS topic
     * @param groupId        message group identifier (idempotencyKey or requestId)
     * @param payload        response object; serialised to JSON
     */
    public void publish(String responseQueue, String groupId, Object payload) {
        if (responseQueue != null && !responseQueue.isBlank()) {
            try {
                String json = objectMapper.writeValueAsString(payload);
                sqsTemplate.send(responseQueue, json);
                log.debug("SQS response sent: queue={} groupId={}", responseQueue, groupId);
            } catch (Exception e) {
                log.error("SQS publish failed, falling back to SNS: queue={} error={}", responseQueue, e.getMessage(), e);
                snsPublisher.publish(groupId, payload);
            }
        } else {
            snsPublisher.publish(groupId, payload);
        }
    }
}
