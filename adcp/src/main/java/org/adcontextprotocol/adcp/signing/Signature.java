package org.adcontextprotocol.adcp.signing;

import java.util.Arrays;
import java.util.Objects;

/**
 * A completed signature produced by {@link SigningProvider}.
 *
 * @param label          signature label, defaults to {@code "sig1"} per AdCP convention
 * @param signatureInput the {@code Signature-Input} header value (RFC 9421 §4.1)
 * @param signatureBytes the raw signature bytes
 * @param algorithm      the JCA algorithm name (e.g. {@code "Ed25519"})
 * @param kid            the key identifier used to produce this signature
 */
public record Signature(String label, String signatureInput, byte[] signatureBytes,
                        String algorithm, String kid) {

    /** Default label per AdCP convention. */
    public static final String DEFAULT_LABEL = "sig1";

    public Signature {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(signatureInput, "signatureInput");
        Objects.requireNonNull(signatureBytes, "signatureBytes");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(kid, "kid");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Signature that)) return false;
        return label.equals(that.label)
                && signatureInput.equals(that.signatureInput)
                && Arrays.equals(signatureBytes, that.signatureBytes)
                && algorithm.equals(that.algorithm)
                && kid.equals(that.kid);
    }

    @Override
    public int hashCode() {
        int result = label.hashCode();
        result = 31 * result + signatureInput.hashCode();
        result = 31 * result + Arrays.hashCode(signatureBytes);
        result = 31 * result + algorithm.hashCode();
        result = 31 * result + kid.hashCode();
        return result;
    }
}