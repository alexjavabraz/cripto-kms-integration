package net.tokeniza.kms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenTransferRequestDto {
    private String event;
    private String idempotencyKey;
    private String requestedAt;
    private String network;
    private Token token;
    private Transfer transfer;
    private Requester requester;
    private Metadata metadata;

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Token {
        private String contractAddress;
        private int decimals = 18;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Transfer {
        private String toAddress;
        private String amount;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Requester {
        private String userId;
        private String email;
        private String ip;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        private String correlationId;
    }
}
