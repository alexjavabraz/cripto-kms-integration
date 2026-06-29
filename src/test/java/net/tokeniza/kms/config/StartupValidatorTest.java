package net.tokeniza.kms.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.*;

class StartupValidatorTest {

    @Test
    void run_passesWithValidConfig() {
        AppProperties props = buildValidProps();
        StartupValidator validator = new StartupValidator(props);
        assertThatCode(() -> validator.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }

    @Test
    void run_failsWhenKeyIdMissing() {
        AppProperties props = buildValidProps();
        props.setKeyId(null);
        StartupValidator validator = new StartupValidator(props);
        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KMS_KEY_ID");
    }

    @Test
    void run_failsWhenKeyIdBlank() {
        AppProperties props = buildValidProps();
        props.setKeyId("  ");
        StartupValidator validator = new StartupValidator(props);
        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KMS_KEY_ID");
    }

    @Test
    void run_failsWhenRpcEndpointMissing() {
        AppProperties props = buildValidProps();
        props.getDlt().setRpcEndpoint(null);
        StartupValidator validator = new StartupValidator(props);
        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DLT_RPC_ENDPOINT");
    }

    @Test
    void run_failsWhenChainIdZero() {
        AppProperties props = buildValidProps();
        props.getDlt().setChainId(0);
        StartupValidator validator = new StartupValidator(props);
        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DLT_CHAIN_ID");
    }

    @Test
    void run_reportsAllMissingFieldsAtOnce() {
        AppProperties props = new AppProperties();
        props.setKeyId(null);
        // rpcEndpoint defaults to null, chainId defaults to 1337 (valid), gasLimit defaults to 300000 (valid)
        StartupValidator validator = new StartupValidator(props);
        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KMS_KEY_ID")
                .hasMessageContaining("DLT_RPC_ENDPOINT");
    }

    private AppProperties buildValidProps() {
        AppProperties props = new AppProperties();
        props.setKeyId("arn:aws:kms:us-east-1:123456789012:key/abc-123");
        props.getDlt().setRpcEndpoint("http://localhost:8545");
        props.getDlt().setChainId(1337L);
        props.getDlt().setGasLimit(300_000L);
        return props;
    }
}
