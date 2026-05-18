package org.adcontextprotocol.adcp.auth;

import org.jspecify.annotations.Nullable;

/**
 * OAuth metadata discovered from an agent, typically via RFC 9728
 * Protected Resource Metadata or from the MCP OAuth flow.
 *
 * @param authorizationEndpoint the OAuth authorization endpoint
 * @param tokenEndpoint         the OAuth token endpoint
 * @param registrationEndpoint  optional dynamic client registration endpoint
 * @param issuer                optional OAuth issuer identifier
 */
public record OAuthMetadataInfo(
        String authorizationEndpoint,
        String tokenEndpoint,
        @Nullable String registrationEndpoint,
        @Nullable String issuer
) {}
