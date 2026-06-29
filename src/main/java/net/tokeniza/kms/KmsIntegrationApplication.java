package net.tokeniza.kms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class KmsIntegrationApplication {
    public static void main(String[] args) {
        SpringApplication.run(KmsIntegrationApplication.class, args);
    }
}
