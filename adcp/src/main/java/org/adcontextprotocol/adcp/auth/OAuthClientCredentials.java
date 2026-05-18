package org.adcontextprotocol.adcp.auth;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * OAuth 2.0 client credentials for the client-credentials grant flow.
 *
 * @param clientId     the OAuth client ID
 * @param clientSecret the OAuth client secret
 * @param tokenEndpoint the token endpoint URI
 * @param scope        optional scope string
 */
public record OAuthClientCredentials(
        String clientId,
        String clientSecret,
        String tokenEndpoint,
        @Nullable String scope
) {

    public OAuthClientCredentials {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
        Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        if (clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
        if (clientSecret.isBlank()) {
            throw new IllegalArgumentException("clientSecret must not be blank");
        }
    }
}
