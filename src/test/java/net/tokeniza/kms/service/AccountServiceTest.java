package net.tokeniza.kms.service;

import net.tokeniza.kms.kms.KmsSignerTest;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    KmsClient kmsClient;

    @InjectMocks
    AccountService accountService;

    @Test
    void createWallet_returnsKeyIdAndEthereumAddress() throws Exception {
        String userId = "user-123";
        String keyId = "arn:aws:kms:us-east-1:123456789:key/abc-123";

        var keyPair = Keys.createEcKeyPair();
        byte[] publicKeyDer = KmsSignerTest.buildPublicKeyDer(keyPair.getPublicKey());
        String expectedAddress = "0x" + Keys.getAddress(keyPair);

        when(kmsClient.createKey(any(CreateKeyRequest.class)))
                .thenReturn(CreateKeyResponse.builder()
                        .keyMetadata(KeyMetadata.builder().keyId(keyId).build())
                        .build());
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(publicKeyDer))
                        .build());
        doNothing().when(kmsClient).createAlias(any(CreateAliasRequest.class));

        AccountService.WalletResult result = accountService.createWallet(userId);

        assertThat(result.keyId()).isEqualTo(keyId);
        assertThat(result.address()).isEqualToIgnoringCase(expectedAddress);
        assertThat(result.address()).matches("0x[0-9a-fA-F]{40}");
    }

    @Test
    void createWallet_createsKeyWithCorrectSpec() throws Exception {
        String userId = "user-456";
        var keyPair = Keys.createEcKeyPair();

        when(kmsClient.createKey(any(CreateKeyRequest.class)))
                .thenReturn(CreateKeyResponse.builder()
                        .keyMetadata(KeyMetadata.builder().keyId("key-id").build())
                        .build());
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(KmsSignerTest.buildPublicKeyDer(keyPair.getPublicKey())))
                        .build());
        doNothing().when(kmsClient).createAlias(any(CreateAliasRequest.class));

        accountService.createWallet(userId);

        ArgumentCaptor<CreateKeyRequest> captor = ArgumentCaptor.forClass(CreateKeyRequest.class);
        verify(kmsClient).createKey(captor.capture());
        assertThat(captor.getValue().keySpec()).isEqualTo(KeySpec.ECC_SECG_P256K1);
        assertThat(captor.getValue().keyUsage()).isEqualTo(KeyUsageType.SIGN_VERIFY);
    }

    @Test
    void createWallet_createsAliasWithUserId() throws Exception {
        String userId = "user-789";
        var keyPair = Keys.createEcKeyPair();

        when(kmsClient.createKey(any(CreateKeyRequest.class)))
                .thenReturn(CreateKeyResponse.builder()
                        .keyMetadata(KeyMetadata.builder().keyId("key-id").build())
                        .build());
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(KmsSignerTest.buildPublicKeyDer(keyPair.getPublicKey())))
                        .build());
        doNothing().when(kmsClient).createAlias(any(CreateAliasRequest.class));

        accountService.createWallet(userId);

        ArgumentCaptor<CreateAliasRequest> captor = ArgumentCaptor.forClass(CreateAliasRequest.class);
        verify(kmsClient).createAlias(captor.capture());
        assertThat(captor.getValue().aliasName()).isEqualTo("alias/tokeniza-user-" + userId);
        assertThat(captor.getValue().targetKeyId()).isEqualTo("key-id");
    }

    @Test
    void createWallet_continuesIfAliasAlreadyExists() throws Exception {
        String userId = "existing-user";
        var keyPair = Keys.createEcKeyPair();

        when(kmsClient.createKey(any(CreateKeyRequest.class)))
                .thenReturn(CreateKeyResponse.builder()
                        .keyMetadata(KeyMetadata.builder().keyId("key-id").build())
                        .build());
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(KmsSignerTest.buildPublicKeyDer(keyPair.getPublicKey())))
                        .build());
        when(kmsClient.createAlias(any(CreateAliasRequest.class)))
                .thenThrow(AlreadyExistsException.builder().message("alias exists").build());

        assertThatCode(() -> accountService.createWallet(userId)).doesNotThrowAnyException();
    }
}
