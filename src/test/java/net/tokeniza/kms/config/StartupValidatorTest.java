package net.tokeniza.kms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.*;

class StartupValidatorTest {

    @Test
    void run_passesWithValidConfig() {
        assertThatCode(() -> new StartupValidator(buildValidProps()).run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void run_failsWhenKeyIdMissing() {
        AppProperties props = buildValidProps();
        props.setKeyId(null);
        assertThatThrownBy(() -> new StartupValidator(props).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KMS_KEY_ID");
    }

    @Test
    void run_failsWhenKeyIdBlank() {
        AppProperties props = buildValidProps();
        props.setKeyId("  ");
        assertThatThrownBy(() -> new StartupValidator(props).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KMS_KEY_ID");
    }

    @Test
    void run_failsWhenRpcEndpointMissing() {
        AppProperties props = buildValidProps();
        props.getDlt().setRpcEndpoint(null);
        assertThatThrownBy(() -> new StartupValidator(props).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DLT_RPC_ENDPOINT");
    }

    @Test
    void run_failsWhenChainIdZero() {
        AppProperties props = buildValidProps();
        props.getDlt().setChainId(0);
        assertThatThrownBy(() -> new StartupValidator(props).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DLT_CHAIN_ID");
    }

    @Test
    void run_failsWhenSnsTopicArnMissing() {
        AppProperties props = buildValidProps();
        props.getSns().setTopicArn(null);
        assertThatThrownBy(() -> new StartupValidator(props).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SNS_TOPIC_ARN");
    }

    @Test
    void run_failsWhenSqsQueueNameBlank() {
        AppProperties props = buildValidProps();
        props.getSqs().setQueueName("  ");
        assertThatThrownBy(() -> new StartupValidator(props).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SQS_QUEUE_NAME");
    }

    @Test
    void run_reportsAllMissingFieldsAtOnce() {
        AppProperties props = new AppProperties();
        // keyId null, rpcEndpoint null, snsTopicArn null — all missing
        assertThatThrownBy(() -> new StartupValidator(props).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KMS_KEY_ID")
                .hasMessageContaining("DLT_RPC_ENDPOINT")
                .hasMessageContaining("SNS_TOPIC_ARN");
    }

    private AppProperties buildValidProps() {
        AppProperties props = new AppProperties();
        props.setKeyId("arn:aws:kms:us-east-1:123456789012:key/abc-123");
        props.getDlt().setRpcEndpoint("http://localhost:8545");
        props.getDlt().setChainId(1337L);
        props.getDlt().setGasLimit(10_000_000L);
        props.getSns().setTopicArn("arn:aws:sns:us-east-1:123456789012:atoken-integracao-kms-sns-dev.fifo");
        props.getSqs().setQueueName("atoken-integracao-kms-sqs-dev.fifo");
        return props;
    }
}
