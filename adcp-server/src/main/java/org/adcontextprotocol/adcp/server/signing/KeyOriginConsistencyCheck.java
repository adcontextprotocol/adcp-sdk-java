package org.adcontextprotocol.adcp.server.signing;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.Map;

/**
 * Key origin consistency check per AdCP #3690.
 *
 * <p>When verifying a signature, the verifier checks that the JWKS source
 * matches the expected origin (e.g., the seller's {@code jwks_uri} from
 * {@code adagents.json} matches the domain the request came from). This
 * defends against the shared-tenancy spoof where an attacker stands up a
 * brand.json that lists a counterparty's legitimate {@code jwks_uri} while
 * the counterparty's own capabilities advertise a different origin.
 *
 * <p>Callers MUST skip this check for publisher-pinned JWKS sources. The
 * check is mandatory only when the JWKS source was the operator brand.json.
 *
 * @see KeyOriginCheckResult
 */
public final class KeyOriginConsistencyCheck {

    private KeyOriginConsistencyCheck() {}

    /**
     * Check that the resolved JWKS URI host matches the declared origin for
     * the given purpose.
     *
     * @param jwksUri     the JWKS URI the verifier resolved via the brand.json chain
     * @param keyOrigins  the {@code identity.key_origins} map from the agent's
     *                    capabilities response; {@code null} is treated as empty
     * @param purpose     the purpose under check (e.g. "request_signing",
     *                    "webhook_signing")
     * @param codeFamily  the error code family: "request" or "webhook"
     * @return a {@link KeyOriginCheckResult}
     */
    public static KeyOriginCheckResult check(
            String jwksUri,
            @Nullable Map<String, String> keyOrigins,
            String purpose,
            String codeFamily) {

        Map<String, String> origins = keyOrigins != null ? keyOrigins : Map.of();
        String declared = origins.get(purpose);

        if (declared == null) {
            String errorCode = "request".equals(codeFamily)
                    ? "request_signature_key_origin_missing"
                    : "webhook_signature_key_origin_missing";
            return new KeyOriginCheckResult.Inconsistent(
                    errorCode,
                    "identity.key_origins." + purpose + " declaration missing");
        }

        String actualHost = extractHost(jwksUri);
        String declaredHost = extractHost(declared);

        if (actualHost == null || declaredHost == null || !actualHost.equals(declaredHost)) {
            String errorCode = "request".equals(codeFamily)
                    ? "request_signature_key_origin_mismatch"
                    : "webhook_signature_key_origin_mismatch";
            String expectedOrigin = declaredHost != null ? declaredHost : declared;
            String actualOrigin = actualHost != null ? actualHost : jwksUri;
            return new KeyOriginCheckResult.Inconsistent(
                    errorCode,
                    "identity.key_origins." + purpose + " declares '"
                            + expectedOrigin + "' but resolved jwks_uri host is '"
                            + actualOrigin + "'");
        }

        return new KeyOriginCheckResult.Consistent();
    }

    /**
     * Overload with default code family "request".
     */
    public static KeyOriginCheckResult check(
            String jwksUri,
            @Nullable Map<String, String> keyOrigins,
            String purpose) {
        return check(jwksUri, keyOrigins, purpose, "request");
    }

    static @Nullable String extractHost(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String stripped = value.strip();

        if (stripped.contains("://")) {
            try {
                URI uri = URI.create(stripped);
                String host = uri.getHost();
                if (host != null && !host.isEmpty()) {
                    return canonicalizeHost(host);
                }
            } catch (Exception e) {
                return null;
            }
        }

        String withScheme = "https://" + stripped;
        try {
            URI uri = URI.create(withScheme);
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                return canonicalizeHost(host);
            }
        } catch (Exception e) {
            return null;
        }

        return null;
    }

    private static String canonicalizeHost(String host) {
        String h = host.toLowerCase();
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        try {
            return java.net.IDN.toASCII(h);
        } catch (Exception e) {
            return h;
        }
    }
}