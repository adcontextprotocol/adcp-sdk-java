package org.adcontextprotocol.adcp.server.signing.revocation;

import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.adcontextprotocol.adcp.server.signing.jws.JwsVerificationResult;
import org.adcontextprotocol.adcp.server.signing.jws.JwsVerifier;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caching revocation list fetcher and checker per the AdCP governance profile.
 *
 * <p>Fetches a revocation list from a URL (e.g.,
 * {@code https://seller.example.com/.well-known/adcp/revocation.jws}),
 * verifies the JWS signature before trusting the content, and caches the
 * list with a grace window. If the list is stale (past
 * {@code next_update + grace_window}), returns {@link RevocationResult.Stale}.
 *
 * <p>Uses single-flight dedup for concurrent fetches to avoid thundering-herd
 * issues on cache expiry.
 *
 * <p>The grace window defaults to 60 seconds per the AdCP spec.
 */
public final class CachingRevocationChecker {

    private static final long DEFAULT_GRACE_WINDOW_SECONDS = 60;
    private static final String REVOCATION_LIST_TYP = "adcp-gov-revocation+jws";
    private static final Set<String> ALLOWED_JWS_ALGS = Set.of("EdDSA", "ES256");

    private final String revocationUri;
    private final VerificationKey verificationKey;
    private final HttpClient httpClient;
    private final long graceWindowSeconds;

    private volatile @Nullable CachedList cachedList;
    private volatile @Nullable Instant lastRefreshAttempt;
    private final ReentrantLock fetchLock = new ReentrantLock();

    /**
     * Create a new caching revocation checker.
     *
     * @param revocationUri    the URL to fetch the revocation list from
     * @param verificationKey   the key to verify the JWS signature
     * @param httpClient        the HTTP client for fetching (SSRF-safe recommended)
     * @param graceWindowSeconds seconds of grace past next_update before stale
     */
    public CachingRevocationChecker(String revocationUri, VerificationKey verificationKey,
            HttpClient httpClient, long graceWindowSeconds) {
        this.revocationUri = revocationUri;
        this.verificationKey = verificationKey;
        this.httpClient = httpClient;
        this.graceWindowSeconds = graceWindowSeconds;
    }

    /**
     * Create a checker with default 60-second grace window.
     */
    public CachingRevocationChecker(String revocationUri, VerificationKey verificationKey,
            HttpClient httpClient) {
        this(revocationUri, verificationKey, httpClient, DEFAULT_GRACE_WINDOW_SECONDS);
    }

    /**
     * Check whether a key identifier has been revoked.
     *
     * <p>If no cached list exists, fetches one. If the cached list is past
     * its {@code next_update}, attempts a refresh. If the list is stale
     * beyond the grace window, returns {@link RevocationResult.Stale}.
     *
     * @param kid the key identifier to check
     * @return the revocation result
     */
    public RevocationResult check(String kid) {
        try {
            ensureFresh();
        } catch (RevocationListStaleException e) {
            return new RevocationResult.Stale(e.staleSeconds());
        }
        CachedList current = cachedList;
        if (current == null) {
            return new RevocationResult.FetchFailed("revocation list not available after fetch attempt");
        }
        if (current.revokedKids().contains(kid)) {
            return new RevocationResult.Revoked(kid);
        }
        return new RevocationResult.Valid();
    }

    /**
     * Check whether a JTI (JSON Token Identifier) has been revoked.
     */
    public boolean isJtiRevoked(String jti) {
        ensureFresh();
        CachedList current = cachedList;
        if (current == null) {
            throw new RevocationListUnavailableException("revocation list not available");
        }
        return current.revokedJtis().contains(jti);
    }

    /**
     * Prime the cache by fetching the revocation list immediately.
     */
    public void prime() {
        fetchAndVerify();
    }

    private void ensureFresh() {
        CachedList current = cachedList;
        if (current == null) {
            fetchAndVerify();
            return;
        }

        Instant nextUpdate = current.nextUpdate();
        Instant now = Instant.now();

        if (now.isBefore(nextUpdate)) {
            return;
        }

        Instant lastAttempt = lastRefreshAttempt;
        if (lastAttempt != null && now.isBefore(lastAttempt.plusSeconds(60))) {
            if (now.isAfter(nextUpdate.plusSeconds(graceWindowSeconds))) {
                long staleSeconds = java.time.Duration.between(nextUpdate.plusSeconds(graceWindowSeconds), now).getSeconds();
                throw new RevocationListStaleException("revocation list past next_update + grace", staleSeconds);
            }
            return;
        }

        fetchAndVerify();
    }

    private void fetchAndVerify() {
        fetchLock.lock();
        try {
            lastRefreshAttempt = Instant.now();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(revocationUri))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/jose+json, application/json, application/jose")
                    .GET()
                    .build();

            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                CachedList current = cachedList;
                if (current != null && Instant.now().isBefore(current.nextUpdate().plusSeconds(graceWindowSeconds))) {
                    return;
                }
                throw new RevocationFetchException("Failed to fetch revocation list: " + e.getMessage(), e);
            }

            if (response.statusCode() == 304) {
                CachedList current = cachedList;
                if (current != null) {
                    Instant now = Instant.now();
                    Duration pollingInterval = Duration.between(current.updated(), current.nextUpdate());
                    cachedList = new CachedList(
                            current.issuer(),
                            current.updated(),
                            current.nextUpdate().plus(pollingInterval),
                            current.revokedKids(),
                            current.revokedJtis()
                    );
                }
                return;
            }

            if (response.statusCode() != 200) {
                CachedList current = cachedList;
                if (current != null && Instant.now().isBefore(current.nextUpdate().plusSeconds(graceWindowSeconds))) {
                    return;
                }
                throw new RevocationFetchException(
                        "Revocation list returned HTTP " + response.statusCode());
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                throw new RevocationFetchException("Revocation list returned empty body");
            }

            JwsVerificationResult jwsResult = JwsVerifier.verify(body, verificationKey, REVOCATION_LIST_TYP);
            if (jwsResult instanceof JwsVerificationResult.Invalid invalid) {
                throw new RevocationFetchException("JWS verification failed: " + invalid.reason());
            }

            JwsVerificationResult.Valid valid = (JwsVerificationResult.Valid) jwsResult;
            String payload = valid.payload();

            RevocationListParsed parsed;
            try {
                parsed = parsePayload(payload);
            } catch (IllegalArgumentException e) {
                throw new RevocationFetchException("Invalid revocation list payload: " + e.getMessage());
            }

            Instant now = Instant.now();
            if (parsed.updated.isAfter(now.plusSeconds(60))) {
                throw new RevocationFetchException("revocation list updated is in the future");
            }
            if (!parsed.nextUpdate.isAfter(parsed.updated)) {
                throw new RevocationFetchException("revocation list next_update is not after updated");
            }

            CachedList current = cachedList;
            if (current != null && parsed.updated.isBefore(current.updated())) {
                throw new RevocationFetchException("revocation list updated is older than cached list");
            }

            cachedList = new CachedList(
                    parsed.issuer,
                    parsed.updated,
                    parsed.nextUpdate,
                    parsed.revokedKids,
                    parsed.revokedJtis
            );
        } finally {
            fetchLock.unlock();
        }
    }

    private RevocationListParsed parsePayload(String payload) {
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload is not valid JSON: " + e.getMessage());
        }

        if (!root.isObject()) {
            throw new IllegalArgumentException("Payload is not a JSON object");
        }

        String issuer = root.path("issuer").asText(null);
        if (issuer == null || issuer.isEmpty()) {
            throw new IllegalArgumentException("Missing 'issuer' field");
        }

        String updatedStr = root.path("updated").asText(null);
        String nextUpdateStr = root.path("next_update").asText(null);
        if (updatedStr == null || nextUpdateStr == null) {
            throw new IllegalArgumentException("Missing 'updated' or 'next_update' field");
        }

        Instant updated = parseInstant(updatedStr);
        Instant nextUpdate = parseInstant(nextUpdateStr);

        Set<String> revokedKids = java.util.Set.of();
        if (root.has("revoked_kids") && root.get("revoked_kids").isArray()) {
            java.util.Set<String> kids = new java.util.HashSet<>();
            for (com.fasterxml.jackson.databind.JsonNode kid : root.get("revoked_kids")) {
                kids.add(kid.asText());
            }
            revokedKids = kids;
        }

        Set<String> revokedJtis = java.util.Set.of();
        if (root.has("revoked_jtis") && root.get("revoked_jtis").isArray()) {
            java.util.Set<String> jtis = new java.util.HashSet<>();
            for (com.fasterxml.jackson.databind.JsonNode jti : root.get("revoked_jtis")) {
                jtis.add(jti.asText());
            }
            revokedJtis = jtis;
        }

        return new RevocationListParsed(issuer, updated, nextUpdate, revokedKids, revokedJtis);
    }

    private static Instant parseInstant(String iso8601) {
        if (iso8601.endsWith("Z")) {
            iso8601 = iso8601.substring(0, iso8601.length() - 1) + "+00:00";
        }
        try {
            return OffsetDateTime.parse(iso8601).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.parse(iso8601);
        }
    }

    private record RevocationListParsed(
            String issuer,
            Instant updated,
            Instant nextUpdate,
            Set<String> revokedKids,
            Set<String> revokedJtis
    ) {}

    private record CachedList(
            String issuer,
            Instant updated,
            Instant nextUpdate,
            Set<String> revokedKids,
            Set<String> revokedJtis
    ) {}

    /**
     * Exception thrown when the revocation list cannot be fetched.
     */
    public static final class RevocationFetchException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public RevocationFetchException(String message) {
            super(message);
        }
        public RevocationFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when the revocation list is stale past the grace window.
     */
    public static final class RevocationListStaleException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private final long staleSeconds;

        public RevocationListStaleException(String message, long staleSeconds) {
            super(message);
            this.staleSeconds = staleSeconds;
        }

        public long staleSeconds() {
            return staleSeconds;
        }
    }

    /**
     * Exception thrown when the revocation list is unavailable.
     */
    public static final class RevocationListUnavailableException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public RevocationListUnavailableException(String message) {
            super(message);
        }
    }
}