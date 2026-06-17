package org.adcontextprotocol.adcp.server.signing.jwks;

import org.adcontextprotocol.adcp.http.AdcpHttpClient;
import org.adcontextprotocol.adcp.http.SsrfBlockedException;
import org.adcontextprotocol.adcp.http.SsrfPolicy;
import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.VerificationException;
import org.adcontextprotocol.adcp.signing.VerificationInput;
import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.adcontextprotocol.adcp.signing.VerificationKeyLookup;
import org.adcontextprotocol.adcp.signing.VerificationKeyResolver;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * HTTP-fetching JWKS resolver with caching, cooldown, and single-flight dedup.
 *
 * <p>On a kid lookup miss, refreshes the JWKS document if the cooldown period
 * has elapsed since the last fetch. If the cooldown has not elapsed, returns
 * {@link VerificationKeyLookup.Missing} immediately — this prevents
 * attack-driven cache invalidation where an attacker forces the verifier to
 * hammer the signer's JWKS endpoint on every rejection.
 *
 * <p>Single-flight dedup: if N threads all miss on the same kid, only one
 * JWKS fetch happens.
 *
 * <p>Stores raw JWK data rather than parsed {@link VerificationKey} objects
 * because {@code adcp_use} validation requires the expected use from the
 * verification input, which is only available at resolve time.
 */
public final class CachingJwksResolver implements VerificationKeyResolver {

    /**
     * Functional interface for JWKS document fetching. Allows test
     * implementations to inject mock responses without extending the
     * final {@link AdcpHttpClient}.
     */
    @FunctionalInterface
    public interface JwksFetcher {
        /** Fetch a JWKS document from the given URI and return the raw bytes. */
        byte[] fetch(URI uri) throws IOException, InterruptedException;
    }

    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);

    private final String jwksUri;
    private final @Nullable AdcpHttpClient httpClient;
    private final @Nullable JwksFetcher fetcher;
    private final Duration cooldown;
    private final SsrfJwksUriValidator uriValidator;

    private volatile @Nullable JwksDocument cached;
    private volatile long lastFetchAttemptNanos;
    private final ConcurrentHashMap<String, CompletableFuture<JwksDocument>> inFlightFetches
            = new ConcurrentHashMap<>();

    /**
     * Create a caching JWKS resolver with default 30-second cooldown.
     *
     * @param jwksUri    the JWKS endpoint URI
     * @param httpClient SSRF-protected HTTP client for fetching JWKS
     */
    public CachingJwksResolver(String jwksUri, AdcpHttpClient httpClient) {
        this(jwksUri, httpClient, null, null);
    }

    /**
     * Create a caching JWKS resolver with configurable cooldown.
     *
     * @param jwksUri    the JWKS endpoint URI
     * @param httpClient SSRF-protected HTTP client for fetching JWKS
     * @param cooldown   minimum time between JWKS re-fetches; null uses 30 seconds
     */
    public CachingJwksResolver(String jwksUri, AdcpHttpClient httpClient,
            @Nullable Duration cooldown) {
        this(jwksUri, httpClient, cooldown, null);
    }

    /**
     * Create a caching JWKS resolver with a custom fetcher (for testing).
     *
     * @param jwksUri    the JWKS endpoint URI
     * @param cooldown   minimum time between JWKS re-fetches; null uses 30 seconds
     * @param fetcher    custom fetcher for JWKS documents
     */
    public CachingJwksResolver(String jwksUri, Duration cooldown, JwksFetcher fetcher) {
        this.jwksUri = Objects.requireNonNull(jwksUri, "jwksUri");
        this.httpClient = null;
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.cooldown = cooldown != null ? cooldown : DEFAULT_COOLDOWN;
        this.uriValidator = new SsrfJwksUriValidator(SsrfPolicy.permissive(), false);
    }

    private CachingJwksResolver(String jwksUri, @Nullable AdcpHttpClient httpClient,
            @Nullable Duration cooldown, @Nullable JwksFetcher fetcher) {
        this.jwksUri = Objects.requireNonNull(jwksUri, "jwksUri");
        this.httpClient = httpClient;
        this.fetcher = fetcher;
        this.cooldown = cooldown != null ? cooldown : DEFAULT_COOLDOWN;
        this.uriValidator = httpClient != null
                ? new SsrfJwksUriValidator(httpClient.ssrfPolicy(), true)
                : new SsrfJwksUriValidator(SsrfPolicy.permissive(), false);
    }

    @Override
    public VerificationKeyLookup resolve(VerificationInput input) {
        String kid = input.kid();
        AdcpUse expectedUse = input.expectedUse();

        JwksDocument doc = cached;
        if (doc != null) {
            VerificationKeyLookup result = lookupWithValidation(doc, kid, expectedUse);
            if (result != null) return result;
        }

        if (shouldRefresh()) {
            doc = refreshWithDedup();
            if (doc != null) {
                VerificationKeyLookup result = lookupWithValidation(doc, kid, expectedUse);
                if (result != null) return result;
            }
        }

        return new VerificationKeyLookup.Missing(kid);
    }

    private @Nullable VerificationKeyLookup lookupWithValidation(
            JwksDocument doc, String kid, AdcpUse expectedUse) {
        Map<String, Object> jwk = doc.get(kid);
        if (jwk == null) return null;
        try {
            VerificationKey key = JwkParser.parse(jwk, expectedUse);
            return new VerificationKeyLookup.Found(key, null, null);
        } catch (VerificationException e) {
            throw new JwksResolutionException(e.errorCode(), e.getMessage(), e);
        }
    }

    /**
     * Get the current cached document, or null if not yet fetched.
     */
    public @Nullable JwksDocument cachedDocument() {
        return cached;
    }

    private boolean shouldRefresh() {
        JwksDocument doc = cached;
        if (doc == null) {
            return true;
        }
        long now = System.nanoTime();
        long elapsedNanos = now - lastFetchAttemptNanos;
        return elapsedNanos >= cooldown.toNanos();
    }

    private @Nullable JwksDocument refreshWithDedup() {
        CompletableFuture<JwksDocument> future = new CompletableFuture<>();
        CompletableFuture<JwksDocument> existing = inFlightFetches.putIfAbsent(
                jwksUri, future);
        if (existing != null) {
            try {
                return existing.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JwksResolutionException(
                        "webhook_signature_jwks_unavailable",
                        "Interrupted waiting for JWKS fetch", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof JwksResolutionException jre) {
                    throw jre;
                }
                throw new JwksResolutionException(
                        "webhook_signature_jwks_unavailable",
                        "JWKS fetch failed: " + cause.getMessage(), cause);
            }
        }
        try {
            JwksDocument doc = doFetch();
            this.cached = doc;
            return doc;
        } finally {
            inFlightFetches.remove(jwksUri, future);
            future.complete(cached);
        }
    }

    private JwksDocument doFetch() {
        lastFetchAttemptNanos = System.nanoTime();
        if (fetcher != null) {
            try {
                uriValidator.validate(URI.create(jwksUri));
            } catch (SsrfBlockedException e) {
                throw new JwksResolutionException(
                        "webhook_signature_jwks_untrusted",
                        "JWKS URI failed SSRF check: " + e.reason(), e);
            }
            byte[] body;
            try {
                body = fetcher.fetch(URI.create(jwksUri));
            } catch (IOException e) {
                throw new JwksResolutionException(
                        "webhook_signature_jwks_unavailable",
                        "JWKS fetch failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JwksResolutionException(
                        "webhook_signature_jwks_unavailable",
                        "Interrupted during JWKS fetch", e);
            }
            return parseJwksDocument(new String(body, StandardCharsets.UTF_8));
        }

        // Use AdcpHttpClient
        try {
            uriValidator.validate(URI.create(jwksUri));
        } catch (SsrfBlockedException e) {
            throw new JwksResolutionException(
                    "webhook_signature_jwks_untrusted",
                    "JWKS URI failed SSRF check: " + e.reason(), e);
        }

        Map<String, String> headers = Map.of("Accept", "application/json");
        byte[] body;
        int statusCode;

        try {
            org.adcontextprotocol.adcp.http.AdcpHttpResponse response =
                    httpClient.get(URI.create(jwksUri), headers);
            statusCode = response.statusCode();
            body = response.body();
        } catch (IOException e) {
            throw new JwksResolutionException(
                    "webhook_signature_jwks_unavailable",
                    "JWKS fetch failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JwksResolutionException(
                    "webhook_signature_jwks_unavailable",
                    "Interrupted during JWKS fetch", e);
        }

        if (statusCode != 200) {
            throw new JwksResolutionException(
                    "webhook_signature_jwks_unavailable",
                    "JWKS fetch returned HTTP " + statusCode);
        }

        return parseJwksDocument(new String(body, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static JwksDocument parseJwksDocument(String json) {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> root;
        try {
            root = mapper.readValue(json, Map.class);
        } catch (IOException e) {
            throw new JwksResolutionException(
                    "webhook_signature_invalid",
                    "Invalid JWKS JSON", e);
        }

        Object keysObj = root.get("keys");
        if (!(keysObj instanceof List<?> keysList)) {
            throw new JwksResolutionException(
                    "webhook_signature_invalid",
                    "JWKS document has no 'keys' array");
        }

        Map<String, Map<String, Object>> keyMap = new LinkedHashMap<>();
        for (Object item : keysList) {
            if (!(item instanceof Map<?, ?>)) continue;
            Map<String, Object> jwk = (Map<String, Object>) item;
            Object kidObj = jwk.get("kid");
            if (kidObj == null) continue;
            String kid = kidObj.toString();
            keyMap.put(kid, jwk);
        }

        return new JwksDocument(keyMap, System.nanoTime());
    }
}