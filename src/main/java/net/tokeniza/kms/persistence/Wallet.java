package net.tokeniza.kms.persistence;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "wallet", indexes = {
        @Index(name = "idx_wallet_user_id", columnList = "user_id"),
        @Index(name = "idx_wallet_address",  columnList = "address")
})
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "key_id", nullable = false, length = 512, unique = true)
    private String keyId;

    @Column(nullable = false, length = 42, unique = true)
    private String address;

    @Column(nullable = false, length = 100)
    private String network;

    @Column(length = 255)
    private String alias;

    /** ADMIN = platform wallet for deployments; USER = end-user wallet for transfers. */
    @Column(nullable = false, length = 20)
    private String role = "USER";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
