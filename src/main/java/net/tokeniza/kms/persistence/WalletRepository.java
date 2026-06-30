package net.tokeniza.kms.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {
    List<Wallet> findByUserId(String userId);
    Optional<Wallet> findByKeyId(String keyId);
    Optional<Wallet> findByAddress(String address);
    boolean existsByKeyId(String keyId);
    Optional<Wallet> findFirstByRole(String role);
}
