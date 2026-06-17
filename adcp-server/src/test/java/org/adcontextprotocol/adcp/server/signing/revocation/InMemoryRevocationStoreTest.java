package org.adcontextprotocol.adcp.server.signing.revocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRevocationStoreTest {

    private InMemoryRevocationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRevocationStore();
    }

    @Test
    void check_unknownKid_returnsValid() {
        RevocationResult result = store.check("unknown-kid");
        assertInstanceOf(RevocationResult.Valid.class, result);
    }

    @Test
    void check_revokedKid_returnsRevoked() {
        store.revoke("kid-revoked");
        RevocationResult result = store.check("kid-revoked");
        assertInstanceOf(RevocationResult.Revoked.class, result);
        assertEquals("kid-revoked", ((RevocationResult.Revoked) result).kid());
    }

    @Test
    void check_staleRevocation_returnsStale() {
        InMemoryRevocationStore store = new InMemoryRevocationStore(1);
        store.revokeAt("kid-stale", System.currentTimeMillis() - 5000);

        RevocationResult result = store.check("kid-stale");
        assertInstanceOf(RevocationResult.Stale.class, result);
        assertTrue(((RevocationResult.Stale) result).staleSeconds() >= 3);
    }

    @Test
    void check_recentRevocation_returnsRevoked() {
        store.revoke("kid-recent");
        RevocationResult result = store.check("kid-recent");
        assertInstanceOf(RevocationResult.Revoked.class, result);
    }

    @Test
    void revoke_multipleKids_independent() {
        store.revoke("kid-1");
        store.revoke("kid-2");

        assertInstanceOf(RevocationResult.Revoked.class, store.check("kid-1"));
        assertInstanceOf(RevocationResult.Revoked.class, store.check("kid-2"));
        assertInstanceOf(RevocationResult.Valid.class, store.check("kid-3"));
    }

    @Test
    void clear_removesAllEntries() {
        store.revoke("kid-1");
        store.revoke("kid-2");
        assertEquals(2, store.size());

        store.clear();
        assertEquals(0, store.size());
        assertInstanceOf(RevocationResult.Valid.class, store.check("kid-1"));
    }

    @Test
    void staleSeconds_calculation() {
        InMemoryRevocationStore store = new InMemoryRevocationStore(1);
        store.revokeAt("kid-stale", System.currentTimeMillis() - 10000);

        RevocationResult result = store.check("kid-stale");
        assertInstanceOf(RevocationResult.Stale.class, result);
        long staleSeconds = ((RevocationResult.Stale) result).staleSeconds();
        assertTrue(staleSeconds >= 0, "staleSeconds should be non-negative");
    }

    @Test
    void stalenessThresholdMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryRevocationStore(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryRevocationStore(-1));
    }

    @Test
    void revokedKid_null_throwsNPE() {
        assertThrows(NullPointerException.class, () -> store.revoke(null));
    }

    @Test
    void check_null_throwsNPE() {
        assertThrows(NullPointerException.class, () -> store.check(null));
    }

    @Test
    void revokedResult_recordEquality() {
        RevocationResult.Revoked r1 = new RevocationResult.Revoked("kid-1");
        RevocationResult.Revoked r2 = new RevocationResult.Revoked("kid-1");
        assertEquals(r1, r2);
        assertEquals(r1.kid(), r2.kid());
    }

    @Test
    void staleResult_recordEquality() {
        RevocationResult.Stale s1 = new RevocationResult.Stale(5);
        RevocationResult.Stale s2 = new RevocationResult.Stale(5);
        assertEquals(s1, s2);
        assertEquals(s1.staleSeconds(), s2.staleSeconds());
    }
}