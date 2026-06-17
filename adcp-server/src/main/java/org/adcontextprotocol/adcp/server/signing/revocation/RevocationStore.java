package org.adcontextprotocol.adcp.server.signing.revocation;

/**
 * Stores revoked key identifiers for revocation checking.
 *
 * <p>Implementations must be thread-safe.
 */
public interface RevocationStore {

    /**
     * Check whether the given kid has been revoked.
     *
     * @param kid the key identifier
     * @return the revocation result
     */
    RevocationResult check(String kid);

    /**
     * Revoke the given kid.
     *
     * @param kid the key identifier to revoke
     */
    void revoke(String kid);

    /**
     * Remove all revocation entries (for testing).
     */
    void clear();
}