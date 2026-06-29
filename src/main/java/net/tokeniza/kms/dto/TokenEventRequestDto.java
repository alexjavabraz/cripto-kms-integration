package net.tokeniza.kms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenEventRequestDto {
    private String idempotencyKey;
    private Network network;
    private Token token;
    private Operation operation;
    private Metadata metadata;

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Network {
        private String name;
        private long chainId;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Token {
        private String address;
        private String standard;
        private int decimals = 18;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Operation {
        private String type;    // mint | burn | transfer | pause | unpause
        private String toAddress;
        private String fromAddress;
        private String amount;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        private String correlationId;
    }
}
