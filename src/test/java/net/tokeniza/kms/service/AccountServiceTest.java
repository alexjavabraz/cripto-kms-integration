package net.tokeniza.kms.service;

import net.tokeniza.kms.TestCryptoUtils;
import net.tokeniza.kms.persistence.Wallet;
import net.tokeniza.kms.persistence.WalletService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock KmsClient     kmsClient;
    @Mock WalletService walletService;

    @InjectMocks
    AccountService accountService;

    private static final String USER_ID  = "user-123";
    private static final String NETWORK  = "besu-local";
    private static final String KEY_ID   = "arn:aws:kms:us-east-1:123456789:key/abc-123";
    private static final String ALIAS    = "alias/tokeniza-user-" + USER_ID;

    private void stubKms(byte[] publicKeyDer) {
        when(kmsClient.createKey(any(CreateKeyRequest.class)))
                .thenReturn(CreateKeyResponse.builder()
                        .keyMetadata(KeyMetadata.builder().keyId(KEY_ID).build())
                        .build());
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(publicKeyDer))
                        .build());
        when(kmsClient.createAlias(any(CreateAliasRequest.class)))
                .thenReturn(CreateAliasResponse.builder().build());
    }

    private Wallet fakeWallet(String userId, String keyId, String address) {
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        w.setUserId(userId);
        w.setKeyId(keyId);
        w.setAddress(address);
        w.setNetwork(NETWORK);
        w.setAlias(ALIAS);
        w.setCreatedAt(Instant.now());
        return w;
    }

    @Test
    void createWallet_returnsKeyIdAndEthereumAddress() throws Exception {
        var keyPair = Keys.createEcKeyPair();
        byte[] publicKeyDer = TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey());
        String expectedAddress = "0x" + Keys.getAddress(keyPair);

        stubKms(publicKeyDer);
        when(walletService.save(any(), any(), any(), any(), any()))
                .thenReturn(fakeWallet(USER_ID, KEY_ID, expectedAddress));

        AccountService.WalletResult result = accountService.createWallet(USER_ID, NETWORK);

        assertThat(result.keyId()).isEqualTo(KEY_ID);
        assertThat(result.address()).isEqualToIgnoringCase(expectedAddress);
        assertThat(result.address()).matches("0x[0-9a-fA-F]{40}");
    }

    @Test
    void createWallet_createsKeyWithCorrectSpec() throws Exception {
        var keyPair = Keys.createEcKeyPair();
        stubKms(TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey()));
        when(walletService.save(any(), any(), any(), any(), any()))
                .thenReturn(fakeWallet(USER_ID, KEY_ID, "0x" + Keys.getAddress(keyPair)));

        accountService.createWallet(USER_ID, NETWORK);

        ArgumentCaptor<CreateKeyRequest> captor = ArgumentCaptor.forClass(CreateKeyRequest.class);
        verify(kmsClient).createKey(captor.capture());
        assertThat(captor.getValue().keySpec()).isEqualTo(KeySpec.ECC_SECG_P256_K1);
        assertThat(captor.getValue().keyUsage()).isEqualTo(KeyUsageType.SIGN_VERIFY);
    }

    @Test
    void createWallet_createsAliasWithUserId() throws Exception {
        var keyPair = Keys.createEcKeyPair();
        stubKms(TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey()));
        when(walletService.save(any(), any(), any(), any(), any()))
                .thenReturn(fakeWallet(USER_ID, KEY_ID, "0x" + Keys.getAddress(keyPair)));

        accountService.createWallet(USER_ID, NETWORK);

        ArgumentCaptor<CreateAliasRequest> captor = ArgumentCaptor.forClass(CreateAliasRequest.class);
        verify(kmsClient).createAlias(captor.capture());
        assertThat(captor.getValue().aliasName()).isEqualTo(ALIAS);
        assertThat(captor.getValue().targetKeyId()).isEqualTo(KEY_ID);
    }

    @Test
    void createWallet_persistsWalletWithCorrectFields() throws Exception {
        var keyPair = Keys.createEcKeyPair();
        String expectedAddress = "0x" + Keys.getAddress(keyPair);
        stubKms(TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey()));
        when(walletService.save(any(), any(), any(), any(), any()))
                .thenReturn(fakeWallet(USER_ID, KEY_ID, expectedAddress));

        accountService.createWallet(USER_ID, NETWORK);

        verify(walletService).save(
                eq(USER_ID),
                eq(KEY_ID),
                argThat(addr -> addr.equalsIgnoreCase(expectedAddress)),
                eq(NETWORK),
                eq(ALIAS)
        );
    }

    @Test
    void createWallet_continuesIfAliasAlreadyExists() throws Exception {
        var keyPair = Keys.createEcKeyPair();
        String address = "0x" + Keys.getAddress(keyPair);
        when(kmsClient.createKey(any(CreateKeyRequest.class)))
                .thenReturn(CreateKeyResponse.builder()
                        .keyMetadata(KeyMetadata.builder().keyId(KEY_ID).build())
                        .build());
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .publicKey(SdkBytes.fromByteArray(TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey())))
                        .build());
        when(kmsClient.createAlias(any(CreateAliasRequest.class)))
                .thenThrow(AlreadyExistsException.builder().message("alias exists").build());
        when(walletService.save(any(), any(), any(), any(), any()))
                .thenReturn(fakeWallet(USER_ID, KEY_ID, address));

        assertThatCode(() -> accountService.createWallet(USER_ID, NETWORK)).doesNotThrowAnyException();
        verify(walletService).save(any(), any(), any(), any(), any());
    }

    @Test
    void createWallet_includesNetworkInDescription() throws Exception {
        var keyPair = Keys.createEcKeyPair();
        stubKms(TestCryptoUtils.buildPublicKeyDer(keyPair.getPublicKey()));
        when(walletService.save(any(), any(), any(), any(), any()))
                .thenReturn(fakeWallet(USER_ID, KEY_ID, "0x" + Keys.getAddress(keyPair)));

        accountService.createWallet(USER_ID, NETWORK);

        ArgumentCaptor<CreateKeyRequest> captor = ArgumentCaptor.forClass(CreateKeyRequest.class);
        verify(kmsClient).createKey(captor.capture());
        assertThat(captor.getValue().description()).contains(USER_ID);
    }
}
