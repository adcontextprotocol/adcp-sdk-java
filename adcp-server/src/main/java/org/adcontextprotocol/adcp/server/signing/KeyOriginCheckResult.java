package org.adcontextprotocol.adcp.server.signing;

import org.jspecify.annotations.Nullable;

/**
 * Sealed interface for key origin consistency check results.
 *
 * @see KeyOriginConsistencyCheck
 */
public sealed interface KeyOriginCheckResult {

    /**
     * The JWKS URI host matches the declared origin for the purpose.
     */
    record Consistent() implements KeyOriginCheckResult {}

    /**
     * The JWKS URI host does not match the declared origin, or the purpose
     * is missing from the key_origins map.
     *
     * @param errorCode the spec error code (e.g.
     *                  {@code request_signature_key_origin_mismatch} or
     *                  {@code request_signature_key_origin_missing})
     * @param reason    human-readable description of the mismatch
     */
    record Inconsistent(String errorCode, String reason) implements KeyOriginCheckResult {
        public Inconsistent {
            if (errorCode == null) throw new NullPointerException("errorCode");
            if (reason == null) throw new NullPointerException("reason");
        }
    }
}