package org.adcontextprotocol.adcp.server.signing.revocation;

/**
 * Sealed interface for revocation lookup results.
 *
 * <p>Matches AdCP test vectors 017 (key-revoked) and 019 (revocation-stale).
 */
public sealed interface RevocationResult {

    /**
     * The key is valid — not revoked and not stale.
     */
    record Valid() implements RevocationResult {}

    /**
     * The key has been revoked.
     *
     * @param kid the revoked key identifier
     */
    record Revoked(String kid) implements RevocationResult {
        public Revoked {
            if (kid == null) throw new NullPointerException("kid");
        }
    }

    /**
     * The key's revocation entry is stale (older than the staleness threshold).
     * The key should be treated as neither confirmed-revoked nor confirmed-valid.
     *
     * @param staleSeconds how many seconds past the staleness threshold
     */
    record Stale(long staleSeconds) implements RevocationResult {
        public Stale {
            if (staleSeconds < 0) throw new IllegalArgumentException("staleSeconds must be non-negative, got: " + staleSeconds);
        }
    }
}