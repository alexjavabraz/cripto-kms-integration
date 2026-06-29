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
        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        wallet.setKeyId(keyId);
        wallet.setAddress(address);
        wallet.setNetwork(network);
        wallet.setAlias(alias);
        Wallet saved = repository.save(wallet);
        log.info("Wallet persisted: userId={} keyId={} address={} network={}", userId, keyId, address, network);
        return saved;
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
