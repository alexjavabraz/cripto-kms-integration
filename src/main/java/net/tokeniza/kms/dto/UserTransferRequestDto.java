package net.tokeniza.kms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserTransferRequestDto {
    private String event;
    private String requestId;
    private String userId;
    private String userWalletId;  // KMS Key ID of the user's wallet
    private String fromAddress;
    private String toAddress;
    private String contractAddress;
    private String network;
    private String amount;
    private int decimals = 18;
    private Metadata metadata;

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        private String correlationId;
    }
}
