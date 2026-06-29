package net.tokeniza.kms.kms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KmsSignerTest {

    @Mock
    KmsClient kmsClient;

    KmsSigner signer;
    ECKeyPair keyPair;
    String expectedAddress;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = Keys.createEcKeyPair();
        expectedAddress = "0x" + Keys.getAddress(keyPair);
        signer = new KmsSigner(kmsClient, "test-key-id");
    }

    @Test
    void getAddress_derivesCorrectEthereumAddress() {
        mockPublicKey(buildPublicKeyDer(keyPair.getPublicKey()));

        String address = signer.getAddress();

        assertThat(address).isEqualToIgnoringCase(expectedAddress);
        assertThat(address).matches("0x[0-9a-fA-F]{40}");
    }

    @Test
    void getAddress_cachedAfterFirstCall() {
        mockPublicKey(buildPublicKeyDer(keyPair.getPublicKey()));

        signer.getAddress();
        signer.getAddress();
        signer.getAddress();

        verify(kmsClient, times(1)).getPublicKey(any(GetPublicKeyRequest.class));
    }

    @Test
    void sign_returnsValidSignatureComponents() throws Exception {
        mockPublicKey(buildPublicKeyDer(keyPair.getPublicKey()));

        byte[] digest = Hash.sha3("test message for signing".getBytes());
        Sign.SignatureData webSig = Sign.signMessage(digest, keyPair, false);
        byte[] derSig = buildDerSignature(
                new BigInteger(1, webSig.getR()),
                new BigInteger(1, webSig.getS()));
        mockSign(derSig);

        Sign.SignatureData result = signer.sign(digest);

        assertThat(result.getR()).hasSize(32);
        assertThat(result.getS()).hasSize(32);
        assertThat(result.getV()[0]).isIn((byte) 0, (byte) 1);
    }

    @Test
    void sign_recIdRecoveredCorrectly() throws Exception {
        mockPublicKey(buildPublicKeyDer(keyPair.getPublicKey()));

        byte[] digest = Hash.sha3("recovery id test".getBytes());
        Sign.SignatureData webSig = Sign.signMessage(digest, keyPair, false);
        BigInteger r = new BigInteger(1, webSig.getR());
        BigInteger s = new BigInteger(1, webSig.getS());
        mockSign(buildDerSignature(r, s));

        Sign.SignatureData result = signer.sign(digest);

        // verify the recovered address matches the expected key
        BigInteger recovered = Sign.recoverFromSignature(
                result.getV()[0],
                new org.web3j.crypto.ECDSASignature(
                        new BigInteger(1, result.getR()),
                        new BigInteger(1, result.getS())),
                digest);
        assertThat(recovered).isNotNull();
        assertThat("0x" + Keys.getAddress(recovered)).isEqualToIgnoringCase(expectedAddress);
    }

    @Test
    void sign_rejectsNon32ByteDigest() {
        assertThatThrownBy(() -> signer.sign(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");

        assertThatThrownBy(() -> signer.sign(new byte[33]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void sign_normalises_highS() throws Exception {
        mockPublicKey(buildPublicKeyDer(keyPair.getPublicKey()));

        // Build a signature with high-s by negating: s_high = N - s_low
        byte[] digest = Hash.sha3("high-s test".getBytes());
        Sign.SignatureData webSig = Sign.signMessage(digest, keyPair, false);
        BigInteger r = new BigInteger(1, webSig.getR());
        BigInteger sLow = new BigInteger(1, webSig.getS());
        BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
        BigInteger sHigh = N.subtract(sLow); // this is the high-s equivalent

        mockSign(buildDerSignature(r, sHigh));

        Sign.SignatureData result = signer.sign(digest);

        BigInteger resultS = new BigInteger(1, result.getS());
        BigInteger halfN = N.divide(BigInteger.TWO);
        assertThat(resultS).isLessThanOrEqualTo(halfN);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void mockPublicKey(byte[] der) {
        GetPublicKeyResponse response = GetPublicKeyResponse.builder()
                .publicKey(SdkBytes.fromByteArray(der))
                .build();
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class))).thenReturn(response);
    }

    private void mockSign(byte[] derSig) {
        SignResponse response = SignResponse.builder()
                .signature(SdkBytes.fromByteArray(derSig))
                .build();
        when(kmsClient.sign(any(SignRequest.class))).thenReturn(response);
    }

    /**
     * Builds a minimal SubjectPublicKeyInfo DER blob for a secp256k1 uncompressed public key.
     * KmsSigner extracts the last 65 bytes: 0x04 || x || y.
     */
    static byte[] buildPublicKeyDer(BigInteger publicKey) {
        // SubjectPublicKeyInfo prefix for EC secp256k1 (23 bytes)
        byte[] prefix = Numeric.hexStringToByteArray("3056301006072a8648ce3d020106052b8104000a034200");
        byte[] xy = Numeric.toBytesPadded(publicKey, 64);
        byte[] der = new byte[prefix.length + 1 + 64]; // = 88 bytes
        System.arraycopy(prefix, 0, der, 0, prefix.length);
        der[prefix.length] = 0x04; // uncompressed point marker
        System.arraycopy(xy, 0, der, prefix.length + 1, 64);
        return der;
    }

    /**
     * Encodes (r, s) as a DER SEQUENCE { INTEGER r, INTEGER s }.
     */
    static byte[] buildDerSignature(BigInteger r, BigInteger s) {
        byte[] rb = r.toByteArray();
        byte[] sb = s.toByteArray();
        int seqLen = 2 + rb.length + 2 + sb.length;
        byte[] der = new byte[2 + seqLen];
        int i = 0;
        der[i++] = 0x30;
        der[i++] = (byte) seqLen;
        der[i++] = 0x02;
        der[i++] = (byte) rb.length;
        System.arraycopy(rb, 0, der, i, rb.length);
        i += rb.length;
        der[i++] = 0x02;
        der[i++] = (byte) sb.length;
        System.arraycopy(sb, 0, der, i, sb.length);
        return der;
    }
}
