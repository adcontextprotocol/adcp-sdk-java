package org.adcontextprotocol.adcp.server.signing.jwks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.adcontextprotocol.adcp.http.AdcpHttpClient;
import org.adcontextprotocol.adcp.http.SsrfBlockedException;
import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.VerificationException;
import org.adcontextprotocol.adcp.signing.VerificationInput;
import org.adcontextprotocol.adcp.signing.VerificationKeyLookup;
import org.adcontextprotocol.adcp.signing.VerificationKeyResolver;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves JWKS via brand.json walk: brand.json → agent entry → jwks_uri → JWKS document.
 *
 * <p>Implements a 3-hop resolution:
 * <ol>
 *   <li>Fetch brand.json from the configured URL</li>
 *   <li>Select the agent entry matching the configured type/id</li>
 *   <li>Fetch the JWKS from the agent's jwks_uri</li>
 * </ol>
 *
 * <p>Caches at each hop level (brand.json cache, JWKS cache). If a kid is not
 * found in the cached JWKS, refreshes the inner JWKS first, then brand.json
 * (in case jwks_uri changed).
 *
 * <p>The brand.json walk ensures the verifier never trusts agent-attested keys
 * directly — keys are always sourced through the operator's brand.json.
 */
public final class BrandJsonJwksResolver implements VerificationKeyResolver {

    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_REDIRECTS = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BARE_HOSTNAME_RE =
            Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$");

    private final String brandJsonUrl;
    private final String agentType;
    private final @Nullable String agentId;
    private final @Nullable String brandId;
    private final AdcpHttpClient httpClient;
    private final SsrfJwksUriValidator uriValidator;
    private final Duration cooldown;

    private volatile @Nullable BrandJsonSnapshot brandJsonSnapshot;
    private volatile @Nullable CachingJwksResolver innerResolver;
    private volatile @Nullable String selectedJwksUri;

    /**
     * Create a brand.json-backed JWKS resolver.
     *
     * @param brandJsonUrl the brand.json URL
     * @param agentType    the agent type to select (e.g. "buying", "creative")
     * @param httpClient   SSRF-protected HTTP client
     */
    public BrandJsonJwksResolver(String brandJsonUrl, String agentType,
            AdcpHttpClient httpClient) {
        this(brandJsonUrl, agentType, null, null, httpClient, null);
    }

    /**
     * Create a brand.json-backed JWKS resolver with full configuration.
     *
     * @param brandJsonUrl the brand.json URL
     * @param agentType    the agent type to select
     * @param agentId      optional agent ID to disambiguate multiple agents of the same type
     * @param brandId      optional brand ID for portfolio brand.json documents
     * @param httpClient   SSRF-protected HTTP client
     * @param cooldown     minimum time between re-fetches; null uses 30 seconds
     */
    public BrandJsonJwksResolver(String brandJsonUrl, String agentType,
            @Nullable String agentId, @Nullable String brandId,
            AdcpHttpClient httpClient, @Nullable Duration cooldown) {
        this.brandJsonUrl = brandJsonUrl;
        this.agentType = agentType;
        this.agentId = agentId;
        this.brandId = brandId;
        this.httpClient = httpClient;
        this.uriValidator = new SsrfJwksUriValidator(httpClient.ssrfPolicy(), true);
        this.cooldown = cooldown != null ? cooldown : DEFAULT_COOLDOWN;
    }

    @Override
    public VerificationKeyLookup resolve(VerificationInput input) {
        // Ensure brand.json is fetched at least once
        if (brandJsonSnapshot == null || innerResolver == null) {
            refreshBrandJson();
        }

        CachingJwksResolver resolver = innerResolver;
        if (resolver == null) {
            return new VerificationKeyLookup.Missing(input.kid());
        }

        VerificationKeyLookup result = resolver.resolve(input);
        if (result instanceof VerificationKeyLookup.Found) {
            return result;
        }

        // Kid not found in cached JWKS. Try refreshing inner JWKS then brand.json.
        if (shouldRefreshBrandJson()) {
            try {
                refreshBrandJson();
                resolver = innerResolver;
                if (resolver != null) {
                    return resolver.resolve(input);
                }
            } catch (JwksResolutionException e) {
                // Keep stale data on transient failure
            }
        }

        return new VerificationKeyLookup.Missing(input.kid());
    }

    /**
     * The JWKS URI selected from brand.json's agents[] for this resolver's
     * (agentType, agentId, brandId) tuple. Populated after the first successful
     * brand.json fetch; null on cold cache.
     */
    public @Nullable String jwksUri() {
        return selectedJwksUri;
    }

    private boolean shouldRefreshBrandJson() {
        BrandJsonSnapshot snap = brandJsonSnapshot;
        if (snap == null) return true;
        long elapsed = System.nanoTime() - snap.fetchedAtNanos;
        return elapsed >= cooldown.toNanos();
    }

    private void refreshBrandJson() {
        String url = brandJsonUrl;

        for (int hop = 0; hop <= DEFAULT_MAX_REDIRECTS; hop++) {
            try {
                uriValidator.validate(URI.create(url));
            } catch (SsrfBlockedException e) {
                throw new JwksResolutionException(
                        "webhook_signature_jwks_untrusted",
                        "brand.json URL failed SSRF check: " + e.reason(), e);
            }

            Map<String, String> headers = Map.of("Accept", "application/json");
            byte[] body;
            int statusCode;

            try {
                org.adcontextprotocol.adcp.http.AdcpHttpResponse response =
                        httpClient.get(URI.create(url), headers);
                statusCode = response.statusCode();
                body = response.body();
            } catch (IOException e) {
                throw new JwksResolutionException(
                        "webhook_signature_jwks_unavailable",
                        "brand.json fetch failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JwksResolutionException(
                        "webhook_signature_jwks_unavailable",
                        "Interrupted during brand.json fetch", e);
            }

            if (statusCode != 200) {
                throw new JwksResolutionException(
                        "webhook_signature_jwks_unavailable",
                        "brand.json fetch returned HTTP " + statusCode);
            }

            JsonNode root;
            try {
                root = MAPPER.readTree(body);
            } catch (IOException e) {
                throw new JwksResolutionException(
                        "webhook_signature_invalid",
                        "brand.json response is not valid JSON", e);
            }

            if (!root.isObject()) {
                throw new JwksResolutionException(
                        "webhook_signature_invalid",
                        "brand.json response is not an object");
            }

            // Check for redirects: authoritative_location or house
            JsonNode authoritative = root.get("authoritative_location");
            if (authoritative != null && authoritative.isTextual()) {
                url = canonicalizeUrl(authoritative.asText());
                continue;
            }

            JsonNode house = root.get("house");
            if (house != null && house.isTextual()) {
                String houseStr = house.asText();
                if (!BARE_HOSTNAME_RE.matcher(houseStr).matches()) {
                    throw new JwksResolutionException(
                            "webhook_signature_invalid",
                            "brand.json 'house' is not a bare hostname");
                }
                url = canonicalizeUrl("https://" + houseStr + "/.well-known/brand.json");
                continue;
            }

            // Terminal document — select agent and build inner resolver
            String jwksUri = selectAgent(root, url);
            selectedJwksUri = jwksUri;
            brandJsonSnapshot = new BrandJsonSnapshot(url, System.nanoTime());

            String currentJwksUri = selectedJwksUri;
            if (innerResolver == null || !jwksUri.equals(currentJwksUri)) {
                innerResolver = new CachingJwksResolver(jwksUri, httpClient, cooldown);
            }
            return;
        }

        throw new JwksResolutionException(
                "webhook_signature_invalid",
                "brand.json redirect depth exceeded");
    }

    private String selectAgent(JsonNode root, String finalUrl) {
        JsonNode agents = findAgents(root);

        if (agents == null || !agents.isArray()) {
            throw new JwksResolutionException(
                    "webhook_signature_invalid",
                    "brand.json has no agents array");
        }

        JsonNode matched = null;
        for (JsonNode agent : agents) {
            if (!agent.isObject()) continue;
            JsonNode typeNode = agent.get("type");
            if (typeNode == null || !agentType.equals(typeNode.asText())) continue;
            if (agentId != null) {
                JsonNode idNode = agent.get("id");
                if (idNode == null || !agentId.equals(idNode.asText())) continue;
            }
            if (matched != null && agentId == null) {
                throw new JwksResolutionException(
                        "webhook_signature_invalid",
                        "brand.json has multiple agents of type '" + agentType
                                + "'; pass agentId to disambiguate");
            }
            matched = agent;
        }

        if (matched == null) {
            throw new JwksResolutionException(
                    "webhook_signature_invalid",
                    "brand.json has no agent matching type=" + agentType);
        }

        JsonNode jwksUriNode = matched.get("jwks_uri");
        if (jwksUriNode != null && jwksUriNode.isTextual()) {
            return jwksUriNode.asText();
        }

        // Default: <agent_origin>/.well-known/jwks.json
        JsonNode urlNode = matched.get("url");
        if (urlNode == null || !urlNode.isTextual()) {
            throw new JwksResolutionException(
                    "webhook_signature_invalid",
                    "brand.json agent has no url or jwks_uri");
        }
        String agentUrl = urlNode.asText();
        return deriveDefaultJwksUri(agentUrl, finalUrl);
    }

    private JsonNode findAgents(JsonNode root) {
        if (brandId != null) {
            JsonNode brands = root.get("brands");
            if (brands != null && brands.isArray()) {
                for (JsonNode brand : brands) {
                    if (brand.isObject() && brandId.equals(brand.get("id").asText())) {
                        JsonNode agents = brand.get("agents");
                        if (agents != null) return agents;
                    }
                }
            }
        }

        JsonNode house = root.get("house");
        if (house != null && house.isObject()) {
            JsonNode agents = house.get("agents");
            if (agents != null) return agents;
        }

        return root.get("agents");
    }

    private String deriveDefaultJwksUri(String agentUrl, String finalBrandUrl) {
        try {
            URI agentUri = URI.create(agentUrl);
            URI brandUri = URI.create(finalBrandUrl);

            String agentOrigin = agentUri.getScheme() + "://" + agentUri.getAuthority();
            String brandOrigin = brandUri.getScheme() + "://" + brandUri.getAuthority();

            if (!agentOrigin.equals(brandOrigin)) {
                throw new JwksResolutionException(
                        "webhook_signature_invalid",
                        "agent.url origin (" + agentOrigin
                                + ") does not match brand.json origin (" + brandOrigin
                                + "); publisher must declare an explicit jwks_uri");
            }
            return agentOrigin + "/.well-known/jwks.json";
        } catch (IllegalArgumentException e) {
            throw new JwksResolutionException(
                    "webhook_signature_invalid",
                    "agent.url is not a valid URL", e);
        }
    }

    private String canonicalizeUrl(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "https";
        String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
        int port = uri.getPort();
        String path = uri.getPath() != null ? uri.getPath() : "/";
        String query = uri.getQuery();

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (port > 0 && !(("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80))) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?').append(query);
        }
        return sb.toString();
    }

    private record BrandJsonSnapshot(String finalUrl, long fetchedAtNanos) {}
}