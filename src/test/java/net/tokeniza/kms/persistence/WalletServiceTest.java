package net.tokeniza.kms.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    WalletRepository repository;

    @InjectMocks
    WalletService walletService;

    private Wallet buildWallet(String userId, String keyId, String address, String network, String alias) {
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        w.setUserId(userId);
        w.setKeyId(keyId);
        w.setAddress(address);
        w.setNetwork(network);
        w.setAlias(alias);
        w.setCreatedAt(Instant.now());
        return w;
    }

    @Test
    void save_persistsAllFields() {
        String userId  = "user-123";
        String keyId   = "arn:aws:kms:us-east-1:123456789012:key/abc";
        String address = "0xAbCdEf0123456789AbCdEf0123456789AbCdEf01";
        String network = "besu-local";
        String alias   = "alias/tokeniza-user-user-123";

        Wallet persisted = buildWallet(userId, keyId, address, network, alias);
        when(repository.save(any(Wallet.class))).thenReturn(persisted);

        Wallet result = walletService.save(userId, keyId, address, network, alias);

        ArgumentCaptor<Wallet> captor = ArgumentCaptor.forClass(Wallet.class);
        verify(repository).save(captor.capture());

        Wallet saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getKeyId()).isEqualTo(keyId);
        assertThat(saved.getAddress()).isEqualTo(address);
        assertThat(saved.getNetwork()).isEqualTo(network);
        assertThat(saved.getAlias()).isEqualTo(alias);

        assertThat(result.getId()).isEqualTo(persisted.getId());
    }

    @Test
    void findByUserId_returnsAllWalletsForUser() {
        String userId = "user-123";
        List<Wallet> wallets = List.of(
                buildWallet(userId, "key-1", "0xAAAA000000000000000000000000000000000001", "besu-local", "alias/tokeniza-user-user-123"),
                buildWallet(userId, "key-2", "0xBBBB000000000000000000000000000000000002", "sepolia",    "alias/tokeniza-user-user-123-2")
        );
        when(repository.findByUserId(userId)).thenReturn(wallets);

        List<Wallet> result = walletService.findByUserId(userId);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(w -> w.getUserId().equals(userId));
    }

    @Test
    void findByUserId_returnsEmptyListWhenNone() {
        when(repository.findByUserId("unknown")).thenReturn(List.of());
        assertThat(walletService.findByUserId("unknown")).isEmpty();
    }

    @Test
    void findByKeyId_returnsWalletWhenFound() {
        String keyId = "arn:aws:kms:us-east-1:123456789012:key/abc";
        Wallet wallet = buildWallet("user-123", keyId, "0xAAAA000000000000000000000000000000000001", "besu-local", null);
        when(repository.findByKeyId(keyId)).thenReturn(Optional.of(wallet));

        Optional<Wallet> result = walletService.findByKeyId(keyId);

        assertThat(result).isPresent();
        assertThat(result.get().getKeyId()).isEqualTo(keyId);
    }

    @Test
    void findByKeyId_returnsEmptyWhenNotFound() {
        when(repository.findByKeyId("nonexistent")).thenReturn(Optional.empty());
        assertThat(walletService.findByKeyId("nonexistent")).isEmpty();
    }

    @Test
    void findByAddress_returnsWalletWhenFound() {
        String address = "0xAbCdEf0123456789AbCdEf0123456789AbCdEf01";
        Wallet wallet = buildWallet("user-123", "key-1", address, "besu-local", null);
        when(repository.findByAddress(address)).thenReturn(Optional.of(wallet));

        Optional<Wallet> result = walletService.findByAddress(address);

        assertThat(result).isPresent();
        assertThat(result.get().getAddress()).isEqualTo(address);
    }

    @Test
    void existsByKeyId_returnsTrueWhenExists() {
        when(repository.existsByKeyId("key-exists")).thenReturn(true);
        assertThat(walletService.existsByKeyId("key-exists")).isTrue();
    }

    @Test
    void existsByKeyId_returnsFalseWhenNotExists() {
        when(repository.existsByKeyId("key-missing")).thenReturn(false);
        assertThat(walletService.existsByKeyId("key-missing")).isFalse();
    }
}
