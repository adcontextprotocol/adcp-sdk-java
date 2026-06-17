package org.adcontextprotocol.adcp.server.signing.webhook;

/**
 * Sealed interface for webhook challenge verification results.
 *
 * @see WebhookChallengeVerifier
 */
public sealed interface ChallengeVerificationResult {

    /**
     * Challenge verification succeeded.
     */
    record Valid() implements ChallengeVerificationResult {}

    /**
     * Challenge verification failed.
     *
     * @param errorCode the error code
     * @param reason    human-readable description
     */
    record Invalid(String errorCode, String reason) implements ChallengeVerificationResult {
        public Invalid {
            if (errorCode == null) throw new NullPointerException("errorCode");
            if (reason == null) throw new NullPointerException("reason");
        }
    }
}