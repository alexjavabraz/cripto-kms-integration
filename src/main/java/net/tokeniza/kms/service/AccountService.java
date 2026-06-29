package net.tokeniza.kms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.tokeniza.kms.kms.KmsSigner;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final KmsClient kmsClient;

    public record WalletResult(String keyId, String address) {}

    /**
     * Creates a new KMS asymmetric key (secp256k1 / SIGN_VERIFY) for a user and
     * returns its Key ID and derived Ethereum address.
     * The Key ID is stored as the user's walletId in the BFF.
     */
    public WalletResult createWallet(String userId) {
        log.info("Creating KMS wallet for userId={}", userId);

        CreateKeyResponse createResponse = kmsClient.createKey(CreateKeyRequest.builder()
                .keySpec(KeySpec.ECC_SECG_P256K1)
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .description("Tokeniza user wallet — userId=" + userId)
                .build());

        String keyId = createResponse.keyMetadata().keyId();

        // Add alias for easier identification
        try {
            kmsClient.createAlias(CreateAliasRequest.builder()
                    .aliasName("alias/tokeniza-user-" + userId)
                    .targetKeyId(keyId)
                    .build());
        } catch (AlreadyExistsException e) {
            log.warn("KMS alias already exists for userId={}", userId);
        }

        // Derive Ethereum address from the KMS public key
        KmsSigner signer = new KmsSigner(kmsClient, keyId);
        String address = signer.getAddress();

        log.info("KMS wallet created: keyId={} address={} userId={}", keyId, address, userId);
        return new WalletResult(keyId, address);
    }
}
