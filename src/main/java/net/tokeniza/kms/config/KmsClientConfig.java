package net.tokeniza.kms.config;

import net.tokeniza.kms.kms.KmsSigner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

import java.net.URI;

@Configuration
public class KmsClientConfig {

    @Bean
    public KmsClient kmsClient(AppProperties props) {
        var builder = KmsClient.builder().region(Region.of(props.getRegion()));
        if (props.getKmsEndpoint() != null && !props.getKmsEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.getKmsEndpoint()));
        }
        return builder.build();
    }

    @Bean
    public KmsSigner platformSigner(KmsClient kmsClient, AppProperties props) {
        // Key ID resolved via supplier so PlatformWalletInitializer can set it before first use
        return new KmsSigner(kmsClient, props::getKeyId);
    }
}
