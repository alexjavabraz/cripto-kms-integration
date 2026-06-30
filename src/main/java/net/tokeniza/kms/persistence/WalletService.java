package net.tokeniza.kms.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository repository;

    @Transactional
    public Wallet save(String userId, String keyId, String address, String network, String alias) {
        return save(userId, keyId, address, network, alias, "USER");
    }

    @Transactional
    public Wallet save(String userId, String keyId, String address, String network, String alias, String role) {
        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setKeyId(keyId);
        wallet.setAddress(address);
        wallet.setNetwork(network);
        wallet.setAlias(alias);
        wallet.setRole(role);
        Wallet saved = repository.save(wallet);
        log.info("Wallet persisted: userId={} keyId={} address={} network={} role={}", userId, keyId, address, network, role);
        return saved;
    }

    public Optional<Wallet> findAdminWallet() {
        return repository.findFirstByRole("ADMIN");
    }

    public Optional<Wallet> findByClientId(String clientId) {
        return repository.findFirstByUserId(clientId);
    }

    public List<Wallet> findByUserId(String userId) {
        return repository.findByUserId(userId);
    }

    public Optional<Wallet> findByKeyId(String keyId) {
        return repository.findByKeyId(keyId);
    }

    public Optional<Wallet> findByAddress(String address) {
        return repository.findByAddress(address);
    }

    public boolean existsByKeyId(String keyId) {
        return repository.existsByKeyId(keyId);
    }
}
