package org.adcontextprotocol.adcp.server.signing.jws;

import org.jspecify.annotations.Nullable;

import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Sealed interface for JWS verification results.
 *
 * @see JwsVerifier
 */
public sealed interface JwsVerificationResult {

    /**
     * JWS signature verified successfully and payload decoded.
     *
     * @param payload the decoded payload as a string
     * @param kid     the key identifier from the JWS header
     */
    record Valid(String payload, String kid) implements JwsVerificationResult {
        public Valid {
            if (payload == null) throw new NullPointerException("payload");
            if (kid == null) throw new NullPointerException("kid");
        }
    }

    /**
     * JWS verification failed.
     *
     * @param reason human-readable description of the failure
     */
    record Invalid(String reason) implements JwsVerificationResult {
        public Invalid {
            if (reason == null) throw new NullPointerException("reason");
        }
    }
}