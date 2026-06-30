package net.tokeniza.kms.config;

import net.tokeniza.kms.TestCryptoUtils;
import net.tokeniza.kms.persistence.Wallet;
import net.tokeniza.kms.persistence.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.crypto.Keys;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformWalletInitializerTest {

    @Mock KmsClient     kmsClient;
    @Mock WalletService walletService;

    AppProperties props;
    PlatformWalletInitializer initializer;

    private static final String EXISTING_KEY_ID = "arn:aws:kms:us-east-1:123:key/existing-key";

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        initializer = new PlatformWalletInitializer(props, kmsClient, walletService);
    }

    // ── Scenario 1: KMS_KEY_ID set, wallet already in DB ─────────────────────

    @Test
    void run_keyIdConfigured_walletAlreadyInDB_doesNothing() throws Exception {
        props.setKeyId(EXISTING_KEY_ID);
        when(walletService.existsByKeyId(EXISTING_KEY_ID)).thenReturn(true);

        initializer.run(null);

        verify(walletService, never()).save(any(), any(), any(), any(), any(), any());
        verify(kmsClient, never()).createKey(any(CreateKeyRequest.class));
        assertThat(props.getKeyId()).isEqualTo(EXISTING_KEY_ID);
    }

    // ── Scenario 2: KMS_KEY_ID set, wallet not in DB → register it ───────────

    @Test
    void run_keyIdConfigured_walletNotInDB_registersWallet() throws Exception {
        var keyPair = Keys.createEcKeyPair();
        String expectedAddress = "0x" + Keys.getAddress(keyPair);
        props.setKeyId(EXISTING_KEY_ID);

        when(walletService.existsByKeyId(EXISTING_KEY_ID)).thenReturn(false);
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey())))
                        .build());
        when(walletService.save(any(), any(), any(), any(), any(), any()))
                .thenReturn(fakeWallet(EXISTING_KEY_ID, expectedAddress, "ADMIN"));

        initializer.run(null);

        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        verify(walletService).save(eq("platform"), eq(EXISTING_KEY_ID), any(), eq("platform"),
                eq("alias/tokeniza-platform-admin"), roleCaptor.capture());
        assertThat(roleCaptor.getValue()).isEqualTo("ADMIN");
        assertThat(props.getKeyId()).isEqualTo(EXISTING_KEY_ID);
    }

    // ── Scenario 3: KMS_KEY_ID not set, ADMIN wallet exists in DB ────────────

    @Test
    void run_noKeyIdConfigured_adminWalletInDB_setsKeyIdFromDB() throws Exception {
        Wallet adminWallet = fakeWallet(EXISTING_KEY_ID, "0xABC123", "ADMIN");
        when(walletService.findAdminWallet()).thenReturn(Optional.of(adminWallet));

        initializer.run(null);

        assertThat(props.getKeyId()).isEqualTo(EXISTING_KEY_ID);
        verify(kmsClient, never()).createKey(any(CreateKeyRequest.class));
        verify(walletService, never()).save(any(), any(), any(), any(), any(), any());
    }

    // ── Scenario 4: KMS_KEY_ID not set, no ADMIN wallet → auto-create ────────

    @Test
    void run_noKeyIdConfigured_noAdminWallet_createsKmsKeyAndPersists() throws Exception {
        var keyPair = Keys.createEcKeyPair();
        String newKeyId = "arn:aws:kms:us-east-1:123:key/new-platform-key";
        String expectedAddress = "0x" + Keys.getAddress(keyPair);

        when(walletService.findAdminWallet()).thenReturn(Optional.empty());
        when(kmsClient.createKey(any(CreateKeyRequest.class)))
                .thenReturn(CreateKeyResponse.builder()
                        .keyMetadata(KeyMetadata.builder().keyId(newKeyId).build())
                        .build());
        when(kmsClient.createAlias(any(CreateAliasRequest.class)))
                .thenReturn(CreateAliasResponse.builder().build());
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey())))
                        .build());
        when(walletService.save(any(), any(), any(), any(), any(), any()))
                .thenReturn(fakeWallet(newKeyId, expectedAddress, "ADMIN"));

        initializer.run(null);

        assertThat(props.getKeyId()).isEqualTo(newKeyId);

        ArgumentCaptor<CreateKeyRequest> keyCaptor = ArgumentCaptor.forClass(CreateKeyRequest.class);
        verify(kmsClient).createKey(keyCaptor.capture());
        assertThat(keyCaptor.getValue().keySpec()).isEqualTo(KeySpec.ECC_SECG_P256_K1);
        assertThat(keyCaptor.getValue().keyUsage()).isEqualTo(KeyUsageType.SIGN_VERIFY);

        ArgumentCaptor<CreateAliasRequest> aliasCaptor = ArgumentCaptor.forClass(CreateAliasRequest.class);
        verify(kmsClient).createAlias(aliasCaptor.capture());
        assertThat(aliasCaptor.getValue().aliasName()).isEqualTo("alias/tokeniza-platform-admin");
        assertThat(aliasCaptor.getValue().targetKeyId()).isEqualTo(newKeyId);

        verify(walletService).save(eq("platform"), eq(newKeyId), any(), eq("platform"),
                eq("alias/tokeniza-platform-admin"), eq("ADMIN"));
    }

    // ── Scenario 5: auto-create, KMS alias conflict → continues gracefully ───

    @Test
    void run_noKeyIdConfigured_noAdminWallet_aliasConflict_continuesNormally() throws Exception {
        var keyPair = Keys.createEcKeyPair();
        String newKeyId = "arn:aws:kms:us-east-1:123:key/alias-conflict-key";

        when(walletService.findAdminWallet()).thenReturn(Optional.empty());
        when(kmsClient.createKey(any(CreateKeyRequest.class)))
                .thenReturn(CreateKeyResponse.builder()
                        .keyMetadata(KeyMetadata.builder().keyId(newKeyId).build())
                        .build());
        when(kmsClient.createAlias(any(CreateAliasRequest.class)))
                .thenThrow(AlreadyExistsException.builder().message("alias exists").build());
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey())))
                        .build());
        when(walletService.save(any(), any(), any(), any(), any(), any()))
                .thenReturn(fakeWallet(newKeyId, "0x" + Keys.getAddress(keyPair), "ADMIN"));

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
        assertThat(props.getKeyId()).isEqualTo(newKeyId);
        verify(walletService).save(any(), any(), any(), any(), any(), eq("ADMIN"));
    }

    // ── Scenario 6: address derived from KMS public key is valid Ethereum addr ─

    @Test
    void run_createdWallet_addressIsValidEthereumAddress() throws Exception {
        var keyPair = Keys.createEcKeyPair();
        String newKeyId = "arn:aws:kms:us-east-1:123:key/addr-check-key";

        when(walletService.findAdminWallet()).thenReturn(Optional.empty());
        when(kmsClient.createKey(any(CreateKeyRequest.class)))
                .thenReturn(CreateKeyResponse.builder()
                        .keyMetadata(KeyMetadata.builder().keyId(newKeyId).build())
                        .build());
        when(kmsClient.createAlias(any(CreateAliasRequest.class)))
                .thenReturn(CreateAliasResponse.builder().build());
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey())))
                        .build());

        ArgumentCaptor<String> addressCaptor = ArgumentCaptor.forClass(String.class);
        when(walletService.save(any(), any(), addressCaptor.capture(), any(), any(), any()))
                .thenReturn(fakeWallet(newKeyId, "0x" + Keys.getAddress(keyPair), "ADMIN"));

        initializer.run(null);

        assertThat(addressCaptor.getValue()).matches("(?i)0x[0-9a-fA-F]{40}");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Wallet fakeWallet(String keyId, String address, String role) {
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        w.setUserId("platform");
        w.setKeyId(keyId);
        w.setAddress(address);
        w.setNetwork("platform");
        w.setAlias("alias/tokeniza-platform-admin");
        w.setRole(role);
        w.setCreatedAt(Instant.now());
        return w;
    }
}
