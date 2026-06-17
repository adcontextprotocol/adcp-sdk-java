package org.adcontextprotocol.adcp.server.signing;

import org.jspecify.annotations.Nullable;

/**
 * Sealed interface for verification results.
 *
 * <p>{@link Valid} indicates the signature verified successfully.
 * {@link Invalid} carries the error code from the spec taxonomy
 * (e.g. {@code webhook_signature_invalid},
 * {@code webhook_signature_key_unknown}).
 */
public sealed interface VerificationResult {

    /**
     * Successful verification.
     *
     * @param kid the key identifier that was used
     */
    record Valid(String kid) implements VerificationResult {
        public Valid {
            if (kid == null) throw new NullPointerException("kid");
        }
    }

    /**
     * Failed verification with an error code from the taxonomy.
     *
     * @param errorCode the stable error code (e.g. {@code webhook_signature_invalid})
     * @param message   human-readable description
     */
    record Invalid(String errorCode, @Nullable String message) implements VerificationResult {
        public Invalid {
            if (errorCode == null) throw new NullPointerException("errorCode");
        }
    }
}