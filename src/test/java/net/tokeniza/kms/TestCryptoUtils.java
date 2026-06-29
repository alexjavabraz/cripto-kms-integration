package net.tokeniza.kms;

import org.web3j.utils.Numeric;

import java.math.BigInteger;

/** Shared crypto helpers for unit and integration tests. */
public final class TestCryptoUtils {

    private TestCryptoUtils() {}

    /**
     * Builds a SubjectPublicKeyInfo DER blob for a secp256k1 uncompressed public key.
     * KmsSigner extracts the last 65 bytes: 0x04 || x || y.
     */
    public static byte[] buildPublicKeyDer(BigInteger publicKey) {
        byte[] prefix = Numeric.hexStringToByteArray("3056301006072a8648ce3d020106052b8104000a034200");
        byte[] xy = Numeric.toBytesPadded(publicKey, 64);
        byte[] der = new byte[prefix.length + 1 + 64];
        System.arraycopy(prefix, 0, der, 0, prefix.length);
        der[prefix.length] = 0x04;
        System.arraycopy(xy, 0, der, prefix.length + 1, 64);
        return der;
    }

    /**
     * Encodes (r, s) as a DER SEQUENCE { INTEGER r, INTEGER s }.
     */
    public static byte[] buildDerSignature(BigInteger r, BigInteger s) {
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
