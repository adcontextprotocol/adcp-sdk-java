package org.adcontextprotocol.adcp.server.signing.revocation;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory revocation store using a {@link ConcurrentHashMap}.
 *
 * <p>Maps {@code kid} to revocation timestamp. Entries older than a
 * configurable staleness threshold (default 300 seconds = 5 minutes) are
 * reported as {@link RevocationResult.Stale} rather than
 * {@link RevocationResult.Revoked}.
 *
 * <p>Thread-safe. Suitable for single-instance deployments.
 */
public final class InMemoryRevocationStore implements RevocationStore {

    private static final long DEFAULT_STALENESS_THRESHOLD_SECONDS = 300;

    private final ConcurrentHashMap<String, Long> store = new ConcurrentHashMap<>();
    private final long stalenessThresholdSeconds;

    public InMemoryRevocationStore() {
        this(DEFAULT_STALENESS_THRESHOLD_SECONDS);
    }

    public InMemoryRevocationStore(long stalenessThresholdSeconds) {
        if (stalenessThresholdSeconds <= 0) {
            throw new IllegalArgumentException(
                    "stalenessThresholdSeconds must be positive, got: " + stalenessThresholdSeconds);
        }
        this.stalenessThresholdSeconds = stalenessThresholdSeconds;
    }

    @Override
    public RevocationResult check(String kid) {
        Long revocationTime = store.get(kid);
        if (revocationTime == null) {
            return new RevocationResult.Valid();
        }

        long now = System.currentTimeMillis();
        long ageSeconds = (now - revocationTime) / 1000;

        if (ageSeconds > stalenessThresholdSeconds) {
            long staleSeconds = ageSeconds - stalenessThresholdSeconds;
            return new RevocationResult.Stale(staleSeconds);
        }

        return new RevocationResult.Revoked(kid);
    }

    @Override
    public void revoke(String kid) {
        store.put(kid, System.currentTimeMillis());
    }

    @Override
    public void clear() {
        store.clear();
    }

    /**
     * Return the number of entries currently stored (for testing).
     */
    int size() {
        return store.size();
    }

    /**
     * Revoke a kid with a specific timestamp (for testing).
     */
    void revokeAt(String kid, long timestampMillis) {
        store.put(kid, timestampMillis);
    }
}