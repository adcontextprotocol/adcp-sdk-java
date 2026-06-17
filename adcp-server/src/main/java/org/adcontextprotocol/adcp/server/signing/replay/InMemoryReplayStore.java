package org.adcontextprotocol.adcp.server.signing.replay;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory replay store using a {@link ConcurrentHashMap}.
 *
 * <p>Maps {@code (kid + ":" + nonce)} to insertion timestamp. Evicts entries
 * older than a configurable TTL (default 300 seconds = 5 minutes per AdCP spec).
 *
 * <p>Thread-safe. Suitable for single-instance deployments; multi-instance
 * deployments should use a distributed store backed by Redis or similar.
 */
public final class InMemoryReplayStore implements ReplayStore {

    private static final long DEFAULT_TTL_SECONDS = 300;

    private final ConcurrentHashMap<String, Long> store = new ConcurrentHashMap<>();
    private final long ttlSeconds;

    public InMemoryReplayStore() {
        this(DEFAULT_TTL_SECONDS);
    }

    public InMemoryReplayStore(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be positive, got: " + ttlSeconds);
        }
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public boolean checkAndStore(String kid, String nonce) {
        evictExpired();
        String key = kid + ":" + nonce;
        long now = System.currentTimeMillis();
        Long existing = store.putIfAbsent(key, now);
        return existing != null;
    }

    /**
     * Remove entries older than the TTL threshold.
     */
    void evictExpired() {
        long cutoff = System.currentTimeMillis() - ttlSeconds * 1000;
        store.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /**
     * Return the number of entries currently stored (for testing).
     */
    int size() {
        return store.size();
    }

    /**
     * Remove all entries (for testing).
     */
    void clear() {
        store.clear();
    }
}