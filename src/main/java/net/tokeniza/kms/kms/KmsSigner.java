package net.tokeniza.kms.kms;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DLSequence;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * KmsSigner — wraps an AWS KMS asymmetric key (ECC_SECG_P256K1 / SIGN_VERIFY)
 * and provides Ethereum-compatible signing via web3j's Sign.SignatureData.
 *
 * The signing digest is always a 32-byte Keccak-256 hash.
 * KMS returns a DER-encoded ECDSA signature; this class decodes it, normalises
 * the s value (low-s), recovers the recId by address comparison, and returns
 * the (v, r, s) tuple expected by web3j's TransactionEncoder.
 */
@Slf4j
public class KmsSigner {

    private static final BigInteger SECP256K1_N =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final BigInteger SECP256K1_HALF_N = SECP256K1_N.divide(BigInteger.TWO);

    private final KmsClient kmsClient;
    private final java.util.function.Supplier<String> keyIdSupplier;

    private String cachedAddress;
    private BigInteger cachedPublicKey;

    public KmsSigner(KmsClient kmsClient, String keyId) {
        this.kmsClient = kmsClient;
        this.keyIdSupplier = () -> keyId;
    }

    /** Used by the platform signer so the key ID can be resolved after Spring startup. */
    public KmsSigner(KmsClient kmsClient, java.util.function.Supplier<String> keyIdSupplier) {
        this.kmsClient = kmsClient;
        this.keyIdSupplier = keyIdSupplier;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the checksummed Ethereum address derived from the KMS public key.
     * Result is cached after the first call.
     */
    public synchronized String getAddress() {
        if (cachedAddress == null) {
            BigInteger pubKey = fetchPublicKey();
            cachedPublicKey = pubKey;
            cachedAddress = "0x" + Keys.getAddress(pubKey);
            log.info("KMS wallet address derived: {} keyId={}", cachedAddress, keyIdSupplier.get());
        }
        return cachedAddress;
    }

    /**
     * Signs a 32-byte digest with the KMS key and returns web3j SignatureData (v, r, s).
     * v is 27 + recId (27 or 28) — web3j 4.12 TransactionEncoder.encode() expects this
     * format and internally converts back to recId 0/1 when encoding EIP-1559 transactions.
     */
    public Sign.SignatureData sign(byte[] digest) throws IOException {
        if (digest.length != 32) {
            throw new IllegalArgumentException("Digest must be 32 bytes, got " + digest.length);
        }

        // Ensure address is cached before signing (needed for v recovery)
        getAddress();

        SignResponse response = kmsClient.sign(SignRequest.builder()
                .keyId(keyIdSupplier.get())
                .message(SdkBytes.fromByteArray(digest))
                .messageType(MessageType.DIGEST)
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)
                .build());

        byte[] derSignature = response.signature().asByteArray();
        BigInteger[] rs = decodeDerSignature(derSignature);
        BigInteger r = rs[0];
        BigInteger s = rs[1];

        // Normalise s to low-s (prevents signature malleability)
        if (s.compareTo(SECP256K1_HALF_N) > 0) {
            s = SECP256K1_N.subtract(s);
            log.debug("KMS sign: s normalised to low-s");
        }

        byte[] rBytes = toBytes32(r);
        byte[] sBytes = toBytes32(s);

        // Recover recId by trying 0 and 1, comparing derived address
        for (int recId = 0; recId <= 1; recId++) {
            BigInteger recovered = Sign.recoverFromSignature(
                    recId, new org.web3j.crypto.ECDSASignature(r, s), digest);
            if (recovered != null) {
                String recoveredAddr = "0x" + Keys.getAddress(recovered);
                if (recoveredAddr.equalsIgnoreCase(cachedAddress)) {
                    log.debug("KMS sign: recId={}", recId);
                    return new Sign.SignatureData((byte) (recId + 27), rBytes, sBytes);
                }
            }
        }

        throw new RuntimeException("KMS sign: could not determine recovery id for key " + keyIdSupplier.get());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Fetches the DER-encoded SubjectPublicKeyInfo from KMS and extracts the
     * uncompressed 64-byte public key as a BigInteger (x || y, without the 0x04 prefix).
     */
    private BigInteger fetchPublicKey() {
        GetPublicKeyResponse response = kmsClient.getPublicKey(
                GetPublicKeyRequest.builder().keyId(keyIdSupplier.get()).build());

        byte[] der = response.publicKey().asByteArray();
        // SubjectPublicKeyInfo for secp256k1: last 65 bytes = 0x04 || x (32) || y (32)
        byte[] uncompressed = Arrays.copyOfRange(der, der.length - 65, der.length);
        // Strip 0x04 prefix → 64 bytes (x || y)
        byte[] xy = Arrays.copyOfRange(uncompressed, 1, uncompressed.length);
        return new BigInteger(1, xy);
    }

    /**
     * Decodes a DER-encoded ECDSA signature into [r, s] BigIntegers.
     * Structure: SEQUENCE { INTEGER r, INTEGER s }
     */
    private static BigInteger[] decodeDerSignature(byte[] der) throws IOException {
        try (ASN1InputStream decoder = new ASN1InputStream(der)) {
            DLSequence seq = (DLSequence) decoder.readObject();
            ASN1Integer r = (ASN1Integer) seq.getObjectAt(0);
            ASN1Integer s = (ASN1Integer) seq.getObjectAt(1);
            return new BigInteger[]{r.getPositiveValue(), s.getPositiveValue()};
        }
    }

    /**
     * Pads or trims a BigInteger to exactly 32 bytes (big-endian, unsigned).
     */
    private static byte[] toBytes32(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == 32) {
            return bytes;
        }
        if (bytes.length > 32) {
            // BigInteger may have a leading 0x00 sign byte
            return Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        }
        byte[] padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
        return padded;
    }
}
