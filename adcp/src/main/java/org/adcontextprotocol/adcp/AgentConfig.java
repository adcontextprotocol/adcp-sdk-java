package org.adcontextprotocol.adcp;

import org.adcontextprotocol.adcp.auth.BasicCredentials;
import org.adcontextprotocol.adcp.auth.OAuthClientCredentials;
import org.adcontextprotocol.adcp.auth.OAuthTokens;
import org.adcontextprotocol.adcp.error.ConfigurationError;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for connecting to an AdCP agent.
 *
 * <p>Carries the agent URI, transport protocol, auth credentials,
 * and optional settings like request signing and webhook configuration.
 *
 * <p>Auth is mutually exclusive:
 * <ul>
 *   <li>Static Bearer token ({@code authToken})</li>
 *   <li>HTTP Basic ({@code basicAuth})</li>
 *   <li>OAuth client-credentials ({@code oauthClientCredentials})</li>
 *   <li>OAuth auth-code ({@code oauthTokens})</li>
 * </ul>
 *
 * Use {@link #builder()} to construct instances.
 */
public record AgentConfig(
        String id,
        URI agentUri,
        Protocol protocol,
        @Nullable String authToken,
        @Nullable BasicCredentials basicAuth,
        @Nullable OAuthClientCredentials oauthClientCredentials,
        @Nullable OAuthTokens oauthTokens,
        @Nullable String webhookUrlTemplate,
        @Nullable String webhookSecret,
        @Nullable AdcpVersion adcpVersion,
        Map<String, String> extraHeaders
) {

    public AgentConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agentUri, "agentUri");
        Objects.requireNonNull(protocol, "protocol");
        extraHeaders = Map.copyOf(extraHeaders);
        validateAuth(authToken, basicAuth, oauthClientCredentials, oauthTokens);
    }

    /** Creates a builder for {@code AgentConfig}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Shorthand: creates a minimal MCP agent config with no auth. */
    public static AgentConfig mcp(String id, URI agentUri) {
        return builder().id(id).agentUri(agentUri).protocol(Protocol.MCP).build();
    }

    /** Shorthand: creates an MCP agent config with a static Bearer token. */
    public static AgentConfig mcp(String id, URI agentUri, String authToken) {
        return builder()
                .id(id)
                .agentUri(agentUri)
                .protocol(Protocol.MCP)
                .authToken(authToken)
                .build();
    }

    private static void validateAuth(
            @Nullable String authToken,
            @Nullable BasicCredentials basicAuth,
            @Nullable OAuthClientCredentials oauthCC,
            @Nullable OAuthTokens oauthTokens) {

        int count = 0;
        if (authToken != null) count++;
        if (basicAuth != null) count++;
        if (oauthCC != null) count++;
        if (oauthTokens != null) count++;

        if (count > 1) {
            throw new ConfigurationError(
                    "Only one auth mechanism may be set on an AgentConfig "
                            + "(got " + count + ": authToken="
                            + (authToken != null) + ", basicAuth="
                            + (basicAuth != null) + ", oauthClientCredentials="
                            + (oauthCC != null) + ", oauthTokens="
                            + (oauthTokens != null) + ")",
                    "auth");
        }
    }

    // -- Builder --

    public static final class Builder {
        private @Nullable String id;
        private @Nullable URI agentUri;
        private Protocol protocol = Protocol.MCP;
        private @Nullable String authToken;
        private @Nullable BasicCredentials basicAuth;
        private @Nullable OAuthClientCredentials oauthClientCredentials;
        private @Nullable OAuthTokens oauthTokens;
        private @Nullable String webhookUrlTemplate;
        private @Nullable String webhookSecret;
        private @Nullable AdcpVersion adcpVersion;
        private Map<String, String> extraHeaders = Map.of();

        private Builder() {}

        /** Required: unique identifier for this agent. */
        public Builder id(String id) {
            this.id = Objects.requireNonNull(id);
            return this;
        }

        /** Required: the agent's base URI. */
        public Builder agentUri(URI agentUri) {
            this.agentUri = Objects.requireNonNull(agentUri);
            return this;
        }

        /** Transport protocol. Defaults to {@link Protocol#MCP}. */
        public Builder protocol(Protocol protocol) {
            this.protocol = Objects.requireNonNull(protocol);
            return this;
        }

        /** Static Bearer token. Mutually exclusive with other auth. */
        public Builder authToken(@Nullable String authToken) {
            this.authToken = authToken;
            return this;
        }

        /** HTTP Basic credentials. Mutually exclusive with other auth. */
        public Builder basicAuth(@Nullable BasicCredentials basicAuth) {
            this.basicAuth = basicAuth;
            return this;
        }

        /** OAuth client-credentials config. Mutually exclusive with other auth. */
        public Builder oauthClientCredentials(@Nullable OAuthClientCredentials oauthCC) {
            this.oauthClientCredentials = oauthCC;
            return this;
        }

        /** OAuth auth-code tokens. Mutually exclusive with other auth. */
        public Builder oauthTokens(@Nullable OAuthTokens oauthTokens) {
            this.oauthTokens = oauthTokens;
            return this;
        }

        /** Webhook URL template for async task results. */
        public Builder webhookUrlTemplate(@Nullable String webhookUrlTemplate) {
            this.webhookUrlTemplate = webhookUrlTemplate;
            return this;
        }

        /** HMAC-SHA256 secret for webhook verification. */
        public Builder webhookSecret(@Nullable String webhookSecret) {
            this.webhookSecret = webhookSecret;
            return this;
        }

        /** Pin a specific AdCP protocol version. */
        public Builder adcpVersion(@Nullable AdcpVersion adcpVersion) {
            this.adcpVersion = adcpVersion;
            return this;
        }

        /** Extra headers injected into every request to this agent. */
        public Builder extraHeaders(Map<String, String> extraHeaders) {
            this.extraHeaders = Map.copyOf(extraHeaders);
            return this;
        }

        /** Builds the config, validating required fields and auth exclusivity. */
        public AgentConfig build() {
            if (id == null) {
                throw new ConfigurationError("AgentConfig.id is required", "id");
            }
            if (agentUri == null) {
                throw new ConfigurationError("AgentConfig.agentUri is required", "agentUri");
            }
            return new AgentConfig(
                    id, agentUri, protocol,
                    authToken, basicAuth, oauthClientCredentials, oauthTokens,
                    webhookUrlTemplate, webhookSecret, adcpVersion,
                    extraHeaders);
        }
    }
}
