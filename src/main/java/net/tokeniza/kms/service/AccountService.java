package net.tokeniza.kms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.kms.KmsSigner;
import net.tokeniza.kms.persistence.WalletService;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final KmsClient kmsClient;
    private final WalletService walletService;

    public record WalletResult(String keyId, String address) {}

    public WalletResult createWallet(String clientId) {
        log.info("Creating KMS wallet for clientId={}", clientId);

        CreateKeyResponse createResponse = kmsClient.createKey(CreateKeyRequest.builder()
                .keySpec(KeySpec.ECC_SECG_P256_K1)
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .description("Tokeniza client wallet — clientId=" + clientId)
                .build());

        String keyId = createResponse.keyMetadata().keyId();
        String alias = "alias/tokeniza-client-" + clientId;

        try {
            kmsClient.createAlias(CreateAliasRequest.builder()
                    .aliasName(alias)
                    .targetKeyId(keyId)
                    .build());
        } catch (AlreadyExistsException e) {
            log.warn("KMS alias already exists for clientId={}", clientId);
        }

        KmsSigner signer = new KmsSigner(kmsClient, keyId);
        String address = signer.getAddress();

        // EVM address is network-agnostic — stored once, valid across all EVM chains
        walletService.save(clientId, keyId, address, "evm", alias);

        log.info("KMS wallet created: keyId={} address={} clientId={}", keyId, address, clientId);
        return new WalletResult(keyId, address);
    }
}
