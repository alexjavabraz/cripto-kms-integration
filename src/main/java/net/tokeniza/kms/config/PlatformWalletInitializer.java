package net.tokeniza.kms.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.kms.KmsSigner;
import net.tokeniza.kms.persistence.Wallet;
import net.tokeniza.kms.persistence.WalletService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.util.Optional;

/**
 * Runs at startup before StartupValidator (Order 1).
 *
 * - KMS_KEY_ID already set: registers it as ADMIN wallet in DB if not present.
 * - KMS_KEY_ID not set: loads existing ADMIN wallet from DB or creates a new KMS key.
 * Either way, AppProperties.keyId is set so the platform KmsSigner resolves on first use.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PlatformWalletInitializer implements ApplicationRunner {

    private final AppProperties props;
    private final KmsClient kmsClient;
    private final WalletService walletService;

    @Override
    public void run(ApplicationArguments args) {
        String configuredKeyId = props.getKeyId();

        if (configuredKeyId != null && !configuredKeyId.isBlank()) {
            ensureAdminWalletRegistered(configuredKeyId);
            return;
        }

        Optional<Wallet> existing = walletService.findAdminWallet();
        if (existing.isPresent()) {
            Wallet wallet = existing.get();
            props.setKeyId(wallet.getKeyId());
            log.info("Platform admin wallet loaded from DB: keyId={} address={}", wallet.getKeyId(), wallet.getAddress());
            return;
        }

        createAndPersistAdminWallet();
    }

    private void ensureAdminWalletRegistered(String keyId) {
        if (walletService.existsByKeyId(keyId)) {
            log.info("Platform admin wallet already in DB: keyId={}", keyId);
            return;
        }
        KmsSigner signer = new KmsSigner(kmsClient, keyId);
        String address = signer.getAddress();
        walletService.save("platform", keyId, address, "platform", "alias/tokeniza-platform-admin", "ADMIN");
        log.info("Platform admin wallet registered in DB: keyId={} address={}", keyId, address);
    }

    private void createAndPersistAdminWallet() {
        log.info("No platform admin wallet found — creating KMS key for platform admin");

        CreateKeyResponse created = kmsClient.createKey(CreateKeyRequest.builder()
                .keySpec(KeySpec.ECC_SECG_P256_K1)
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .description("Tokeniza platform admin wallet — auto-provisioned at startup")
                .build());

        String keyId = created.keyMetadata().keyId();
        String alias = "alias/tokeniza-platform-admin";

        try {
            kmsClient.createAlias(CreateAliasRequest.builder()
                    .aliasName(alias)
                    .targetKeyId(keyId)
                    .build());
        } catch (AlreadyExistsException e) {
            log.warn("KMS alias {} already exists — skipping alias creation", alias);
        }

        KmsSigner signer = new KmsSigner(kmsClient, keyId);
        String address = signer.getAddress();

        walletService.save("platform", keyId, address, "platform", alias, "ADMIN");
        props.setKeyId(keyId);

        log.info("Platform admin wallet created: keyId={} address={}", keyId, address);
    }
}
