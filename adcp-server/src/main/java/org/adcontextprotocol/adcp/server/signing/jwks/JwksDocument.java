package org.adcontextprotocol.adcp.server.signing.jwks;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;

/**
 * A parsed JWKS document: raw JWK objects indexed by kid, with a fetch
 * timestamp for cooldown calculation.
 *
 * <p>Stores raw JWK data rather than parsed {@link org.adcontextprotocol.adcp.signing.VerificationKey}
 * objects because {@code adcp_use} validation requires the expected use from
 * the verification input, which is only available at resolve time.
 *
 * @param jwksByKeyId raw JWK objects indexed by kid
 * @param lastFetched  monotonic timestamp (nanos) of the last successful fetch
 */
public record JwksDocument(Map<String, Map<String, Object>> jwksByKeyId, long lastFetched) {

    public JwksDocument {
        jwksByKeyId = Collections.unmodifiableMap(jwksByKeyId);
    }

    /**
     * Look up a raw JWK by kid.
     *
     * @return the JWK map, or {@code null} if not found
     */
    public @Nullable Map<String, Object> get(String kid) {
        return jwksByKeyId.get(kid);
    }

    /**
     * Check if the document contains a key with the given kid.
     */
    public boolean containsKid(String kid) {
        return jwksByKeyId.containsKey(kid);
    }
}