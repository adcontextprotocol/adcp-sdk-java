package org.adcontextprotocol.adcp.server.signing.replay;

/**
 * Stores (kid, nonce) pairs for replay detection per RFC 9421 and AdCP 3.1
 * idempotency rules.
 *
 * <p>Implementations must be thread-safe. The {@link #checkAndStore} method
 * is atomic: it returns {@code true} if the (kid, nonce) pair was already
 * present (replay detected), and inserts it if absent.
 */
public interface ReplayStore {

    /**
     * Check whether the given (kid, nonce) pair has been seen before, and
     * store it if not.
     *
     * @param kid   the key identifier
     * @param nonce the nonce from the Signature-Input
     * @return {@code true} if this is a replay (pair already seen),
     *         {@code false} if this is the first occurrence
     */
    boolean checkAndStore(String kid, String nonce);
}