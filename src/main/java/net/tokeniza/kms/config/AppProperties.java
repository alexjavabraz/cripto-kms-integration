package net.tokeniza.kms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "kms")
public class AppProperties {

    private String region = "us-east-1";
    private String keyId;
    private String walletAddress = "";

    private Dlt dlt = new Dlt();
    private Sqs sqs = new Sqs();

    @Data
    public static class Dlt {
        private String rpcEndpoint;
        private long chainId = 1337;
        private String explorerUrl = "";
        private long gasLimit = 300_000;
        private long maxFeePerGas = 0;
        private long maxPriorityFeePerGas = 0;
        private String gasFundAmountEth = "0.001";
    }

    @Data
    public static class Sqs {
        private String tokenCreationRequest  = "kms-token-creation-request";
        private String tokenCreationResponse = "kms-token-creation-response";
        private String balanceRequest        = "kms-balance-request";
        private String balanceResponse       = "kms-balance-response";
        private String tokenEventRequest     = "kms-token-event-request";
        private String tokenEventResponse    = "kms-token-event-response";
        private String tokenTransferRequest  = "kms-token-transfer-request";
        private String tokenTransferResponse = "kms-token-transfer-response";
        private String accountCreateRequest  = "kms-account-create-request";
        private String accountCreateResponse = "kms-account-create-response";
        private String userTransferRequest   = "kms-user-transfer-request";
        private String userTransferResponse  = "kms-user-transfer-response";
    }
}
