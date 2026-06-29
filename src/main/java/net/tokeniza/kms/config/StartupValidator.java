package net.tokeniza.kms.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupValidator implements ApplicationRunner {

    private final AppProperties props;

    @Override
    public void run(ApplicationArguments args) {
        List<String> errors = new ArrayList<>();

        if (blank(props.getKeyId())) {
            errors.add("KMS_KEY_ID (kms.key-id) — platform signing key is required");
        }
        if (blank(props.getDlt().getRpcEndpoint())) {
            errors.add("DLT_RPC_ENDPOINT (kms.dlt.rpc-endpoint) — blockchain RPC URL is required");
        }
        if (props.getDlt().getChainId() <= 0) {
            errors.add("DLT_CHAIN_ID (kms.dlt.chain-id) — must be a positive integer");
        }
        if (props.getDlt().getGasLimit() <= 0) {
            errors.add("DLT_GAS_LIMIT (kms.dlt.gas-limit) — must be a positive integer");
        }
        if (blank(props.getSns().getTopicArn())) {
            errors.add("SNS_TOPIC_ARN (kms.sns.topic-arn) — FIFO SNS topic ARN for publishing responses is required");
        }
        if (blank(props.getSqs().getQueueName())) {
            errors.add("SQS_QUEUE_NAME (kms.sqs.queue-name) — FIFO SQS queue name for reading requests is required");
        }

        if (!errors.isEmpty()) {
            String msg = "Startup validation failed — missing or invalid configuration:\n  - "
                    + String.join("\n  - ", errors);
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        log.info("Startup validation passed — region={} chainId={} rpc={} sqs={} sns={}",
                props.getRegion(), props.getDlt().getChainId(), props.getDlt().getRpcEndpoint(),
                props.getSqs().getQueueName(), props.getSns().getTopicArn());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
