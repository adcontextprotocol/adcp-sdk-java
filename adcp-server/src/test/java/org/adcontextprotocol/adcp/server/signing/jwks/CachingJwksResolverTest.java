package org.adcontextprotocol.adcp.server.signing.jwks;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SignedInput;
import org.adcontextprotocol.adcp.signing.VerificationInput;
import org.adcontextprotocol.adcp.signing.VerificationKeyLookup;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CachingJwksResolverTest {

    private static final String JWKS_JSON = """
            {
              "keys": [
                {
                  "kid": "test-ed25519-webhook-2026",
                  "kty": "OKP",
                  "crv": "Ed25519",
                  "alg": "EdDSA",
                  "use": "sig",
                  "key_ops": ["verify"],
                  "adcp_use": "adcp_whk",
                  "x": "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA"
                },
                {
                  "kid": "test-es256-webhook-2026",
                  "kty": "EC",
                  "crv": "P-256",
                  "alg": "ES256",
                  "use": "sig",
                  "key_ops": ["verify"],
                  "adcp_use": "adcp_whk",
                  "x": "0X7G_jryFpiX9XO3CKxIqUQs3DC8OhUkw6Rb5QOZd5M",
                  "y": "MwZN7qQJzLpTD5dyDJAoqOLZJ9r8-GCh4BnOYu6NE0c"
                }
              ]
            }
            """;

    private static final String JWKS_JSON_UPDATED = """
            {
              "keys": [
                {
                  "kid": "test-ed25519-webhook-2026",
                  "kty": "OKP",
                  "crv": "Ed25519",
                  "alg": "EdDSA",
                  "use": "sig",
                  "key_ops": ["verify"],
                  "adcp_use": "adcp_whk",
                  "x": "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA"
                },
                {
                  "kid": "new-key-2027",
                  "kty": "OKP",
                  "crv": "Ed25519",
                  "alg": "EdDSA",
                  "use": "sig",
                  "key_ops": ["verify"],
                  "adcp_use": "adcp_whk",
                  "x": "VgpQd9JRrBf433BcMw6IUNW7tHnAAHAHegsQ5U9I53c"
                }
              ]
            }
            """;

    private VerificationInput input(String kid) {
        return new VerificationInput(
                AdcpUse.WEBHOOK_SIGNING,
                kid,
                new SignedInput("{}".getBytes(StandardCharsets.UTF_8), Map.of(), "POST", "/")
        );
    }

    @Test
    void resolvesKnownKidFromFetchedJwks() {
        AtomicInteger fetchCount = new AtomicInteger(0);
        byte[] responseBody = JWKS_JSON.getBytes(StandardCharsets.UTF_8);
        CachingJwksResolver resolver = new CachingJwksResolver(
                "https://example.com/.well-known/jwks.json",
                Duration.ofHours(1),
                uri -> {
                    fetchCount.incrementAndGet();
                    return responseBody;
                });

        VerificationKeyLookup result = resolver.resolve(input("test-ed25519-webhook-2026"));
        assertInstanceOf(VerificationKeyLookup.Found.class, result);
        VerificationKeyLookup.Found found = (VerificationKeyLookup.Found) result;
        assertEquals("test-ed25519-webhook-2026", found.key().kid());
        assertEquals("Ed25519", found.key().algorithm());
    }

    @Test
    void returnsMissingForUnknownKid() {
        AtomicInteger fetchCount = new AtomicInteger(0);
        byte[] responseBody = JWKS_JSON.getBytes(StandardCharsets.UTF_8);
        CachingJwksResolver resolver = new CachingJwksResolver(
                "https://example.com/.well-known/jwks.json",
                Duration.ofHours(1),
                uri -> {
                    fetchCount.incrementAndGet();
                    return responseBody;
                });

        VerificationKeyLookup result = resolver.resolve(input("unknown-kid"));
        assertInstanceOf(VerificationKeyLookup.Missing.class, result);
    }

    @Test
    void cachesJwksDocument() {
        AtomicInteger fetchCount = new AtomicInteger(0);
        byte[] responseBody = JWKS_JSON.getBytes(StandardCharsets.UTF_8);
        CachingJwksResolver resolver = new CachingJwksResolver(
                "https://example.com/.well-known/jwks.json",
                Duration.ofHours(1),
                uri -> {
                    fetchCount.incrementAndGet();
                    return responseBody;
                });

        resolver.resolve(input("test-ed25519-webhook-2026"));
        resolver.resolve(input("test-es256-webhook-2026"));

        assertEquals(1, fetchCount.get());
    }

    @Test
    void respectsCooldownDoesNotRefetchWithinWindow() {
        AtomicInteger fetchCount = new AtomicInteger(0);
        byte[] responseBody = JWKS_JSON.getBytes(StandardCharsets.UTF_8);
        CachingJwksResolver resolver = new CachingJwksResolver(
                "https://example.com/.well-known/jwks.json",
                Duration.ofHours(1),
                uri -> {
                    fetchCount.incrementAndGet();
                    return responseBody;
                });

        resolver.resolve(input("test-ed25519-webhook-2026"));
        assertEquals(1, fetchCount.get());

        VerificationKeyLookup result = resolver.resolve(input("unknown-kid"));
        assertInstanceOf(VerificationKeyLookup.Missing.class, result);
        assertEquals(1, fetchCount.get());
    }

    @Test
    void refetchesAfterCooldownElapses() throws Exception {
        AtomicInteger fetchCount = new AtomicInteger(0);
        byte[][] responses = {JWKS_JSON.getBytes(StandardCharsets.UTF_8), JWKS_JSON_UPDATED.getBytes(StandardCharsets.UTF_8)};
        CachingJwksResolver resolver = new CachingJwksResolver(
                "https://example.com/.well-known/jwks.json",
                Duration.ofNanos(1),
                uri -> {
                    int idx = Math.min(fetchCount.getAndIncrement(), responses.length - 1);
                    return responses[idx];
                });

        resolver.resolve(input("test-ed25519-webhook-2026"));
        assertEquals(1, fetchCount.get());

        Thread.sleep(1);

        VerificationKeyLookup result = resolver.resolve(input("new-key-2027"));
        assertInstanceOf(VerificationKeyLookup.Found.class, result);
        assertTrue(fetchCount.get() >= 2);
    }

    @Test
    void rejectsAdcpUseMismatch() {
        String jwksJson = """
                {
                  "keys": [
                    {
                      "kid": "test-wrong-purpose",
                      "kty": "OKP",
                      "crv": "Ed25519",
                      "alg": "EdDSA",
                      "use": "sig",
                      "key_ops": ["verify"],
                      "adcp_use": "adcp_req",
                      "x": "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA"
                    }
                  ]
                }
                """;
        AtomicInteger fetchCount = new AtomicInteger(0);
        CachingJwksResolver resolver = new CachingJwksResolver(
                "https://example.com/.well-known/jwks.json",
                Duration.ofHours(1),
                uri -> {
                    fetchCount.incrementAndGet();
                    return jwksJson.getBytes(StandardCharsets.UTF_8);
                });

        JwksResolutionException ex = assertThrows(JwksResolutionException.class,
                () -> resolver.resolve(input("test-wrong-purpose")));
        assertEquals("webhook_signature_key_purpose_invalid", ex.errorCode());
    }
}