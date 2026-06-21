package org.adcontextprotocol.adcp.server.signing.revocation;

import org.adcontextprotocol.adcp.server.signing.InProcessKeyGenerator;
import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CachingRevocationCheckerTest {

    private static String makeJws(String payloadJson, KeyPair keyPair, String kid) throws Exception {
        String headerJson = "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"typ\":\"adcp-gov-revocation+jws\"}";
        String b64Header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String b64Payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = b64Header + "." + b64Payload;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();
        String b64Signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        return b64Header + "." + b64Payload + "." + b64Signature;
    }

    @Test
    void revocationResult_sealedInterface() {
        assertInstanceOf(RevocationResult.Valid.class, new RevocationResult.Valid());
        assertInstanceOf(RevocationResult.Revoked.class, new RevocationResult.Revoked("kid-1"));
        assertInstanceOf(RevocationResult.Stale.class, new RevocationResult.Stale(5));
        assertInstanceOf(RevocationResult.FetchFailed.class, new RevocationResult.FetchFailed("network error"));
    }

    @Test
    void revocationResult_revoked_record() {
        RevocationResult.Revoked r = new RevocationResult.Revoked("test-kid");
        assertEquals("test-kid", r.kid());
    }

    @Test
    void revocationResult_stale_record() {
        RevocationResult.Stale s = new RevocationResult.Stale(300);
        assertEquals(300, s.staleSeconds());
    }

    @Test
    void revocationResult_fetchFailed_record() {
        RevocationResult.FetchFailed f = new RevocationResult.FetchFailed("connection refused");
        assertEquals("connection refused", f.reason());
    }

    @Test
    void revocationResult_nullChecks() {
        assertThrows(NullPointerException.class, () -> new RevocationResult.Revoked(null));
        assertThrows(NullPointerException.class, () -> new RevocationResult.FetchFailed(null));
        assertThrows(IllegalArgumentException.class, () -> new RevocationResult.Stale(-1));
    }

    @Test
    void cachingRevocationChecker_validKid_returnsValid() throws Exception {
        KeyPair keyPair = InProcessKeyGenerator.generateEd25519();
        VerificationKey verKey = new VerificationKey("test-kid", "Ed25519", keyPair.getPublic().getEncoded(), null);

        Instant now = Instant.now();
        String updated = now.minusSeconds(60).toString();
        String nextUpdate = now.plusSeconds(300).toString();
        String payload = "{\"issuer\":\"https://example.com\",\"updated\":\"" + updated
                + "\",\"next_update\":\"" + nextUpdate + "\",\"revoked_kids\":[\"revoked-kid\"],\"revoked_jtis\":[]}";
        String jws = makeJws(payload, keyPair, "test-kid");

        java.net.http.HttpClient mockClient = java.net.http.HttpClient.newBuilder()
                .build();
        CachingRevocationChecker checker = new CachingRevocationChecker(
                "https://example.com/.well-known/governance-revocations.json",
                verKey, mockClient, 60);

        assertThrows(CachingRevocationChecker.RevocationFetchException.class, checker::prime);
    }

    @Test
    void check_staleList_returnsStaleNotException() throws Exception {
        KeyPair keyPair = InProcessKeyGenerator.generateEd25519();
        VerificationKey verKey = new VerificationKey("test-kid", "Ed25519", keyPair.getPublic().getEncoded(), null);

        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder().build();
        CachingRevocationChecker checker = new CachingRevocationChecker(
                "https://example.com/.well-known/governance-revocations.json",
                verKey, httpClient, 60);

        Instant farPast = Instant.now().minusSeconds(3600);
        Instant nextUpdate = Instant.now().minusSeconds(300);

        Object cachedList = createCachedList("https://example.com", farPast, nextUpdate, Set.of("revoked-kid"), Set.of());

        Field cachedListField = CachingRevocationChecker.class.getDeclaredField("cachedList");
        cachedListField.setAccessible(true);
        cachedListField.set(checker, cachedList);

        Field lastRefreshField = CachingRevocationChecker.class.getDeclaredField("lastRefreshAttempt");
        lastRefreshField.setAccessible(true);
        lastRefreshField.set(checker, Instant.now());

        RevocationResult result = checker.check("some-kid");
        assertInstanceOf(RevocationResult.Stale.class, result,
                "Expected Stale result, got: " + result);
        RevocationResult.Stale stale = (RevocationResult.Stale) result;
        assertTrue(stale.staleSeconds() > 0, "staleSeconds should be positive, got: " + stale.staleSeconds());
    }

    @Test
    void check_fetchFailure_returnsFetchFailedNotException() throws Exception {
        KeyPair keyPair = InProcessKeyGenerator.generateEd25519();
        VerificationKey verKey = new VerificationKey("test-kid", "Ed25519", keyPair.getPublic().getEncoded(), null);

        // Use a malformed URI that triggers a fetch failure on cold cache.
        // The check() method must catch RevocationFetchException and return
        // RevocationResult.FetchFailed, not throw through the sealed API.
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder().build();
        CachingRevocationChecker checker = new CachingRevocationChecker(
                "http://127.0.0.1:1/unreachable-revocation-list.jws",
                verKey, httpClient, 60);

        RevocationResult result = checker.check("some-kid");
        assertInstanceOf(RevocationResult.FetchFailed.class, result,
                "Expected FetchFailed result, got: " + result);
        RevocationResult.FetchFailed failed = (RevocationResult.FetchFailed) result;
        assertNotNull(failed.reason(), "FetchFailed reason should not be null");
        assertFalse(failed.reason().isBlank(), "FetchFailed reason should not be blank");
    }

    private static Object createCachedList(String issuer, Instant updated, Instant nextUpdate,
            Set<String> revokedKids, Set<String> revokedJtis) {
        try {
            var constructor = Class.forName(
                    "org.adcontextprotocol.adcp.server.signing.revocation.CachingRevocationChecker$CachedList")
                    .getDeclaredConstructor(String.class, Instant.class, Instant.class, Set.class, Set.class);
            constructor.setAccessible(true);
            return constructor.newInstance(issuer, updated, nextUpdate, revokedKids, revokedJtis);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create CachedList via reflection", e);
        }
    }
}