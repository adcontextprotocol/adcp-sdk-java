package org.adcontextprotocol.adcp.signing;

import org.jspecify.annotations.Nullable;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Objects;

/**
 * Verification key material for inbound signature verification.
 *
 * <p>Carries the raw key bytes and algorithm metadata. The {@link #asJcaKey()}
 * method lazily converts to a JCA {@link PublicKey} using {@link KeyFactory}.
 *
 * @param kid           key identifier matching the inbound {@code kid} header
 * @param algorithm     JCA algorithm name (e.g. {@code "Ed25519"}, {@code "RSA"})
 * @param publicKeyBytes raw public key bytes (DER or raw depending on algorithm)
 * @param crv           curve name for elliptic algorithms, or {@code null}
 */
public record VerificationKey(String kid, String algorithm, byte[] publicKeyBytes, @Nullable String crv) {

    public VerificationKey {
        Objects.requireNonNull(kid, "kid");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(publicKeyBytes, "publicKeyBytes");
    }

    /**
     * Lazily convert to a JCA {@link PublicKey}.
     *
     * <p>Supports:
     * <ul>
     *   <li>{@code "Ed25519"} — raw 32-byte key (JDK 21+ native)</li>
     *   <li>{@code "RSA"} — DER-encoded SubjectPublicKeyInfo</li>
     * </ul>
     *
     * @return the JCA public key
     * @throws VerificationException if the key cannot be converted
     */
    public @Nullable PublicKey asJcaKey() throws VerificationException {
        try {
            KeyFactory kf = KeyFactory.getInstance(algorithm);
            return kf.generatePublic(new java.security.spec.X509EncodedKeySpec(publicKeyBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new VerificationException("webhook_signature_invalid",
                    "Unsupported key algorithm: " + algorithm, e);
        } catch (java.security.spec.InvalidKeySpecException e) {
            throw new VerificationException("webhook_signature_invalid",
                    "Invalid key specification for algorithm: " + algorithm, e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VerificationKey that)) return false;
        return kid.equals(that.kid)
                && algorithm.equals(that.algorithm)
                && Arrays.equals(publicKeyBytes, that.publicKeyBytes)
                && Objects.equals(crv, that.crv);
    }

    @Override
    public int hashCode() {
        int result = kid.hashCode();
        result = 31 * result + algorithm.hashCode();
        result = 31 * result + Arrays.hashCode(publicKeyBytes);
        result = 31 * result + Objects.hashCode(crv);
        return result;
    }
}