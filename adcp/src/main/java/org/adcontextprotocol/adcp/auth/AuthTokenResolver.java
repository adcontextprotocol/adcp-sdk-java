package org.adcontextprotocol.adcp.auth;

import org.adcontextprotocol.adcp.AgentConfig;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves auth headers from an {@link AgentConfig}.
 *
 * <p>Supports:
 * <ul>
 *   <li>Static Bearer token → {@code Authorization: Bearer} + {@code x-adcp-auth}</li>
 *   <li>HTTP Basic → {@code Authorization: Basic}</li>
 *   <li>OAuth auth-code tokens → {@code Authorization: Bearer}</li>
 *   <li>No auth → empty map</li>
 * </ul>
 *
 * <p>OAuth client-credentials flow (token exchange/refresh) is handled
 * separately by the transport layer before calling this resolver.
 */
public final class AuthTokenResolver {

    private AuthTokenResolver() {}

    /**
     * Resolves the auth headers for the given agent config.
     *
     * @param config the agent configuration
     * @return map of header name → value (may be empty)
     */
    public static Map<String, String> resolve(AgentConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();

        if (config.authToken() != null) {
            // Static bearer token — send both headers for backward compat
            headers.put("Authorization", "Bearer " + config.authToken());
            headers.put("x-adcp-auth", config.authToken());
        } else if (config.basicAuth() != null) {
            // HTTP Basic (7.2.0 delta)
            BasicCredentials creds = config.basicAuth();
            String encoded = Base64.getEncoder().encodeToString(
                    (creds.username() + ":" + creds.password())
                            .getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + encoded);
        } else if (config.oauthTokens() != null) {
            // OAuth auth-code tokens
            headers.put("Authorization", "Bearer " + config.oauthTokens().accessToken());
        }
        // oauthClientCredentials: token exchange is done upstream before resolve()

        return Map.copyOf(headers);
    }
}
