package org.adcontextprotocol.adcp.server.signing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Computes the {@code Content-Digest} header value per RFC 9530.
 *
 * <p>Produces headers like: {@code Content-Digest: sha-256=:base64:}
 *
 * <p>Per the AdCP conformance test vectors, Content-Digest uses standard
 * base64 encoding with padding (RFC 9530 dictionary syntax). The
 * {@code Signature} header uses base64url without padding (RFC 9421 §3.3.2).
 */
public final class ContentDigest {

    public static final String SHA_256 = "sha-256";
    public static final String SHA_512 = "sha-512";

    private ContentDigest() {}

    /**
     * Compute a Content-Digest value for the given body bytes using SHA-256.
     *
     * @param body the raw body bytes
     * @return the digest value string (e.g. {@code sha-256=:dJ2koiIMZIhdGE7tidErCHV13FFvOIowCcXDiwyG54I=:})
     */
    public static String sha256(byte[] body) {
        return compute(SHA_256, body);
    }

    /**
     * Compute a Content-Digest value for the given body bytes using SHA-512.
     *
     * @param body the raw body bytes
     * @return the digest value string (e.g. {@code sha-512=:...:})
     */
    public static String sha512(byte[] body) {
        return compute(SHA_512, body);
    }

    /**
     * Compute a Content-Digest value for the given body bytes.
     *
     * @param algorithm the digest algorithm ({@code "sha-256"} or {@code "sha-512"})
     * @param body the raw body bytes
     * @return the formatted Content-Digest header value
     * @throws IllegalArgumentException if the algorithm is not supported
     */
    public static String compute(String algorithm, byte[] body) {
        String jcaAlgorithm;
        if (SHA_256.equals(algorithm)) {
            jcaAlgorithm = "SHA-256";
        } else if (SHA_512.equals(algorithm)) {
            jcaAlgorithm = "SHA-512";
        } else {
            throw new IllegalArgumentException("Unsupported digest algorithm: " + algorithm);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(jcaAlgorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JCA algorithm not available: " + jcaAlgorithm, e);
        }
        byte[] hash = digest.digest(body);
        String encoded = Base64.getEncoder().encodeToString(hash);
        return algorithm + "=:" + encoded + ":";
    }

    /**
     * Base64url-encode without padding, per RFC 9421 §3.3.2.
     * Used for the Signature header value, not for Content-Digest.
     */
    static String base64UrlNoPadding(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}