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
    private Sns sns = new Sns();
    private Sqs sqs = new Sqs();

    @Data
    public static class Dlt {
        private String rpcEndpoint;
        private long chainId = 1337;
        private String explorerUrl = "";
        private long gasLimit = 10_000_000;
        private long maxFeePerGas = 0;
        private long maxPriorityFeePerGas = 0;
        private String gasFundAmountEth = "0.001";
        /** Compiled contract bytecode — keyed by standard name in uppercase (ERC20, ERC721, ERC1155). */
        private java.util.Map<String, String> bytecode = new java.util.HashMap<>();
    }

    @Data
    public static class Sns {
        /** ARN of the FIFO SNS topic kms-integration publishes responses to. */
        private String topicArn;
    }

    @Data
    public static class Sqs {
        /** Name of the FIFO SQS queue kms-integration reads requests from. */
        private String queueName = "atoken-integracao-kms-sqs-dev.fifo";
    }
}
