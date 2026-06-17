package org.adcontextprotocol.adcp.server.signing.replay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryReplayStoreTest {

    private InMemoryReplayStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryReplayStore();
    }

    @Test
    void firstOccurrence_returnsFalse() {
        boolean result = store.checkAndStore("kid-1", "nonce-abc");
        assertFalse(result, "First occurrence should return false (no replay)");
    }

    @Test
    void secondOccurrence_returnsTrue() {
        store.checkAndStore("kid-1", "nonce-abc");
        boolean result = store.checkAndStore("kid-1", "nonce-abc");
        assertTrue(result, "Second occurrence should return true (replay detected)");
    }

    @Test
    void differentKid_sameNonce_returnsFalse() {
        store.checkAndStore("kid-1", "nonce-abc");
        boolean result = store.checkAndStore("kid-2", "nonce-abc");
        assertFalse(result, "Different kid should be a different entry");
    }

    @Test
    void sameKid_differentNonce_returnsFalse() {
        store.checkAndStore("kid-1", "nonce-abc");
        boolean result = store.checkAndStore("kid-1", "nonce-def");
        assertFalse(result, "Different nonce should be a different entry");
    }

    @Test
    void evictsExpiredEntries() throws InterruptedException {
        InMemoryReplayStore shortLived = new InMemoryReplayStore(1);
        shortLived.checkAndStore("kid-1", "nonce-abc");
        assertEquals(1, shortLived.size());

        Thread.sleep(1100);

        shortLived.evictExpired();
        assertEquals(0, shortLived.size(), "Expired entries should be evicted");
    }

    @Test
    void expiredEntry_notConsideredReplay() throws InterruptedException {
        InMemoryReplayStore shortLived = new InMemoryReplayStore(1);
        shortLived.checkAndStore("kid-1", "nonce-abc");

        Thread.sleep(1100);

        boolean result = shortLived.checkAndStore("kid-1", "nonce-abc");
        assertFalse(result, "Expired entry should not be considered a replay");
    }

    @Test
    void threadSafety_concurrentCheckAndStore() throws Exception {
        int threadCount = 16;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger replayCount = new AtomicInteger(0);

        CountDownLatch done = new CountDownLatch(threadCount);
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int i = 0; i < iterations; i++) {
                        boolean isReplay = store.checkAndStore("kid-thread", "nonce-shared");
                        if (isReplay) {
                            replayCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        int totalOps = threadCount * iterations;
        int replays = replayCount.get();
        assertTrue(replays > 0, "At least one thread should see a replay");
        assertEquals(totalOps, replays + 1,
                "Exactly one thread gets false (first), rest get true (replay)");
        executor.shutdown();
    }

    @Test
    void clear_removesAllEntries() {
        store.checkAndStore("kid-1", "nonce-1");
        store.checkAndStore("kid-2", "nonce-2");
        assertEquals(2, store.size());

        store.clear();
        assertEquals(0, store.size());

        boolean result = store.checkAndStore("kid-1", "nonce-1");
        assertFalse(result, "After clear, entry should be absent");
    }

    @Test
    void ttlMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryReplayStore(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryReplayStore(-1));
    }

    @Test
    void perKidRateLimiting_manyNoncesSameKid() {
        for (int i = 0; i < 100; i++) {
            boolean result = store.checkAndStore("kid-ratelimited", "nonce-" + i);
            assertFalse(result, "Unique nonces should not be considered replays");
        }
        assertEquals(100, store.size());
    }
}