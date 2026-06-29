package net.tokeniza.kms.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequestLogService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RequestLogRepository repository;

    @Transactional
    public RequestLog logReceived(String idempotencyKey, String type, String responseQueue, Object rawPayload) {
        RequestLog entry = new RequestLog();
        entry.setIdempotencyKey(idempotencyKey);
        entry.setType(type);
        entry.setStatus("RECEIVED");
        entry.setResponseQueue(responseQueue);
        entry.setPayload(toJson(rawPayload));
        return repository.save(entry);
    }

    @Transactional
    public void markCompleted(String idempotencyKey, Object response) {
        repository.findByIdempotencyKey(idempotencyKey).ifPresent(entry -> {
            entry.setStatus("COMPLETED");
            entry.setResponse(toJson(response));
            repository.save(entry);
        });
    }

    @Transactional
    public void markFailed(String idempotencyKey, String errorMessage) {
        repository.findByIdempotencyKey(idempotencyKey).ifPresent(entry -> {
            entry.setStatus("FAILED");
            entry.setErrorMessage(errorMessage);
            repository.save(entry);
        });
    }

    private static String toJson(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialise payload to JSON: {}", e.getMessage());
            return obj.toString();
        }
    }
}
