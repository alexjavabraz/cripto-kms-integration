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
    private Queue queue = new Queue();
    private Exchange exchange = new Exchange();

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
    public static class Queue {
        private String tokenCreation = "dfns_listen_token_creation_request";
        private String balance = "queue_get_balance";
        private String tokenEvent = "token_event.queue";
        private String tokenTransfer = "dfns_listen_token_transfer_request";
        private String accountCreate = "dfns_listen_account_create_request";
        private String userTransfer = "dfns_listen_user_transfer_request";
    }

    @Data
    public static class Exchange {
        private String tokenCreationRequest = "bff_publish_token_creation_request";
        private String tokenCreationResponse = "dfns_publish_token_creation_response";
        private String balanceResponse = "balance_response";
        private String tokenEvent = "token_event";
        private String tokenEventResponse = "token_event_response";
        private String tokenTransferRequest = "bff_publish_token_transfer_request";
        private String tokenTransferResponse = "dfns_publish_token_transfer_response";
        private String accountCreateRequest = "bff_publish_account_create_request";
        private String accountCreateResponse = "dfns_publish_account_create_response";
        private String userTransferRequest = "bff_publish_user_transfer_request";
        private String userTransferResponse = "dfns_publish_user_transfer_response";
        private String error = "token_creation_error";
    }
}
