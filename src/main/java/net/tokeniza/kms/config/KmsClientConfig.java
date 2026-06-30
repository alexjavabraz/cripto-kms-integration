package net.tokeniza.kms.config;

import net.tokeniza.kms.kms.KmsSigner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

@Configuration
public class KmsClientConfig {

    @Bean
    public KmsClient kmsClient(AppProperties props) {
        return KmsClient.builder()
                .region(Region.of(props.getRegion()))
                .build();
    }

    @Bean
    public KmsSigner platformSigner(KmsClient kmsClient, AppProperties props) {
        // Key ID resolved via supplier so PlatformWalletInitializer can set it before first use
        return new KmsSigner(kmsClient, props::getKeyId);
    }
}
