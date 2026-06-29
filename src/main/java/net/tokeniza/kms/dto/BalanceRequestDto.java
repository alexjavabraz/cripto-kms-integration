package net.tokeniza.kms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BalanceRequestDto extends BaseRequestDto {
    private String idempotencyKey;
    private Network network;
    private Token token;
    private Wallet wallet;
    private Metadata metadata;

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Network {
        private String name;
        private long chainId;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Token {
        private String address;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Wallet {
        private String address;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        private String correlationId;
    }
}
