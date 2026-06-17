package org.adcontextprotocol.adcp.server.signing.webhook;

/**
 * Sealed interface for HMAC webhook verification results.
 *
 * @see LegacyHmacWebhookVerifier
 */
public sealed interface HmacVerificationResult {

    /**
     * HMAC verification succeeded.
     */
    record Valid() implements HmacVerificationResult {}

    /**
     * HMAC verification failed with a specific error code and reason.
     *
     * @param errorCode the error code (e.g. {@code signature_mismatch},
     *                  {@code missing_header})
     * @param reason    human-readable description
     */
    record Invalid(String errorCode, String reason) implements HmacVerificationResult {
        public Invalid {
            if (errorCode == null) throw new NullPointerException("errorCode");
            if (reason == null) throw new NullPointerException("reason");
        }
    }

    /**
     * The timestamp in the X-AdCP-Timestamp header is outside the accepted window.
     */
    record StaleTimestamp(long skewSeconds) implements HmacVerificationResult {
        public StaleTimestamp {
            if (skewSeconds < 0) throw new IllegalArgumentException("skewSeconds must be non-negative");
        }
    }

    /**
     * A replay was detected — the same (timestamp, nonce) combination was
     * already processed within the window.
     */
    record ReplayDetected() implements HmacVerificationResult {}

    /**
     * The secret for this sender was not found.
     */
    record SecretNotFound() implements HmacVerificationResult {}
}