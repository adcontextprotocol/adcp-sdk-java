package org.adcontextprotocol.adcp.server.signing.jwks;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.VerificationException;
import org.adcontextprotocol.adcp.signing.VerificationInput;
import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.adcontextprotocol.adcp.signing.VerificationKeyLookup;
import org.adcontextprotocol.adcp.signing.VerificationKeyResolver;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * In-memory resolver from a pre-loaded JWKS document. Useful for testing
 * and for known seller keys.
 *
 * <p>No cooldown, no network fetches. Direct lookup.
 */
public final class StaticJwksResolver implements VerificationKeyResolver {

    private final JwksDocument document;

    /**
     * Create a static resolver from a kid-to-JWK map.
     *
     * @param jwksByKeyId kid to raw JWK mapping
     */
    public StaticJwksResolver(Map<String, Map<String, Object>> jwksByKeyId) {
        this.document = new JwksDocument(Objects.requireNonNull(jwksByKeyId, "jwksByKeyId"),
                System.nanoTime());
    }

    @Override
    public VerificationKeyLookup resolve(VerificationInput input) {
        Map<String, Object> jwk = document.get(input.kid());
        if (jwk == null) {
            return new VerificationKeyLookup.Missing(input.kid());
        }
        try {
            VerificationKey key = JwkParser.parse(jwk, input.expectedUse());
            return new VerificationKeyLookup.Found(key, null, null);
        } catch (VerificationException e) {
            throw new JwksResolutionException(e.errorCode(), e.getMessage(), e);
        }
    }
}