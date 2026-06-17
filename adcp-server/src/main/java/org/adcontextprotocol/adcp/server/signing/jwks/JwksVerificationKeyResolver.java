package org.adcontextprotocol.adcp.server.signing.jwks;

import org.adcontextprotocol.adcp.http.AdcpHttpClient;
import org.adcontextprotocol.adcp.signing.VerificationException;
import org.adcontextprotocol.adcp.signing.VerificationInput;
import org.adcontextprotocol.adcp.signing.VerificationKeyLookup;
import org.adcontextprotocol.adcp.signing.VerificationKeyResolver;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Main JWKS verification key resolver. Implements {@link VerificationKeyResolver}
 * by fetching keys from a JWKS endpoint with caching and cooldown.
 *
 * <p>On {@link #resolve(VerificationInput)}:
 * <ol>
 *   <li>Look up the kid from the cached JWKS document</li>
 *   <li>If found, return {@link VerificationKeyLookup.Found} with a
 *       {@link org.adcontextprotocol.adcp.signing.VerificationKey} built from the JWK</li>
 *   <li>If not found and cooldown has elapsed, re-fetch the JWKS and try again</li>
 *   <li>If still not found, return {@link VerificationKeyLookup.Missing}</li>
 * </ol>
 *
 * <p>Delegates caching and network logic to {@link CachingJwksResolver}.
 */
public final class JwksVerificationKeyResolver implements VerificationKeyResolver {

    private final CachingJwksResolver delegate;

    /**
     * Create a resolver with default 30-second cooldown.
     *
     * @param jwksUri    the JWKS endpoint URI
     * @param httpClient SSRF-protected HTTP client for fetching JWKS
     */
    public JwksVerificationKeyResolver(String jwksUri, AdcpHttpClient httpClient) {
        this(jwksUri, httpClient, null);
    }

    /**
     * Create a resolver with configurable cooldown.
     *
     * @param jwksUri    the JWKS endpoint URI
     * @param httpClient SSRF-protected HTTP client for fetching JWKS
     * @param cooldown   minimum time between JWKS re-fetches; null uses 30 seconds
     */
    public JwksVerificationKeyResolver(String jwksUri, AdcpHttpClient httpClient,
            @Nullable Duration cooldown) {
        this.delegate = new CachingJwksResolver(jwksUri, httpClient, cooldown);
    }

    /**
     * Expose the underlying CachingJwksResolver for testing.
     */
    CachingJwksResolver cachingResolver() {
        return delegate;
    }

    @Override
    public VerificationKeyLookup resolve(VerificationInput input) {
        return delegate.resolve(input);
    }
}