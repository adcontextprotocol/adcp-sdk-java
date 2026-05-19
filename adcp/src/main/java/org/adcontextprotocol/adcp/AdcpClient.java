package org.adcontextprotocol.adcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.adcontextprotocol.adcp.error.ConfigurationError;
import org.adcontextprotocol.adcp.http.SsrfPolicy;
import org.adcontextprotocol.adcp.schema.AdcpObjectMapperFactory;
import org.adcontextprotocol.adcp.transport.CallToolOptions;
import org.adcontextprotocol.adcp.transport.ProtocolClient;
import org.adcontextprotocol.adcp.transport.mcp.McpConnectionManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * The main user-facing AdCP client. Single-agent, all tool methods
 * funnel through {@link ProtocolClient#callTool}.
 *
 * <p>Usage:
 * <pre>{@code
 * try (AdcpClient client = AdcpClient.builder()
 *         .agent(AgentConfig.mcp("seller", URI.create("https://agent.example.com")))
 *         .build()) {
 *     var resp = client.callTool("get_products", args, GetProductsResponse.class);
 * }
 * }</pre>
 *
 * <p>Named convenience methods (e.g. {@code getProducts()}) are provided
 * for each tool in the TS SDK parity target. All funnel through
 * {@link #callTool(String, Map, Class, CallToolOptions)}.
 */
public final class AdcpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AdcpClient.class);

    private final AgentConfig agent;
    private final ProtocolClient protocolClient;
    private final ObjectMapper objectMapper;
    private final @Nullable AdcpVersion adcpVersion;

    private AdcpClient(Builder builder) {
        if (builder.agent == null) {
            throw new ConfigurationError("AdcpClient.agent is required", "agent");
        }
        this.agent = builder.agent;
        this.adcpVersion = builder.adcpVersion;

        this.objectMapper = builder.objectMapper != null
                ? builder.objectMapper
                : AdcpObjectMapperFactory.create();

        SsrfPolicy ssrfPolicy = builder.ssrfPolicy != null
                ? builder.ssrfPolicy
                : SsrfPolicy.strict();

        McpConnectionManager connectionManager = new McpConnectionManager();
        this.protocolClient = new ProtocolClient(
                this.objectMapper, ssrfPolicy, adcpVersion, connectionManager);
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    // -- Generic tool call --

    /**
     * Calls a tool with explicit options.
     *
     * @param toolName     the MCP tool name (e.g. "get_products")
     * @param args         tool arguments
     * @param args         tool arguments (may be {@code null}, treated as empty)
     * @param responseType expected response type
     * @param options      call options
     * @param <T>          response type
     * @return deserialized response
     */
    public <T> T callTool(String toolName, @Nullable Map<String, Object> args,
                          Class<T> responseType, CallToolOptions options) {
        return protocolClient.callTool(agent, toolName,
                args != null ? args : Map.of(), responseType, options);
    }

    /**
     * Calls a tool with default options.
     */
    public <T> T callTool(String toolName, @Nullable Map<String, Object> args,
                          Class<T> responseType) {
        return callTool(toolName, args, responseType, CallToolOptions.DEFAULT);
    }

    // -- Named convenience methods (TS SDK parity) --
    // Each converts a typed request to a Map and delegates to callTool.

    /**
     * Converts a request object to a Map for the tool call.
     * Uses the ObjectMapper to handle the conversion.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toArgs(Object request) {
        return objectMapper.convertValue(request, Map.class);
    }

    /**
     * Calls a named tool with a typed request object.
     *
     * @param toolName     the MCP tool name
     * @param request      the typed request object (will be serialized to Map)
     * @param responseType expected response type
     * @param <T>          response type
     * @return deserialized response
     */
    public <T> T callNamedTool(String toolName, Object request,
                               Class<T> responseType) {
        return callTool(toolName, toArgs(request), responseType);
    }

    // -- Lifecycle --

    /** Returns the agent config this client is bound to. */
    public AgentConfig agent() {
        return agent;
    }

    /** Returns the protocol version in use. */
    public @Nullable AdcpVersion adcpVersion() {
        return adcpVersion;
    }

    @Override
    public void close() {
        protocolClient.close();
    }

    // -- Builder --

    public static final class Builder {
        private @Nullable AgentConfig agent;
        private @Nullable AdcpVersion adcpVersion;
        private @Nullable ObjectMapper objectMapper;
        private @Nullable SsrfPolicy ssrfPolicy;

        private Builder() {}

        /** Required: the agent to connect to. */
        public Builder agent(AgentConfig agent) {
            this.agent = Objects.requireNonNull(agent);
            return this;
        }

        /** Pin a specific AdCP protocol version. */
        public Builder adcpVersion(AdcpVersion adcpVersion) {
            this.adcpVersion = adcpVersion;
            return this;
        }

        /**
         * Pin a specific AdCP protocol version by release-precision string
         * (e.g. {@code "3.0"}, {@code "3.1"}).
         *
         * <p>Equivalent to {@code adcpVersion(AdcpVersion.of(releaseVersion))}.
         * Throws {@link org.adcontextprotocol.adcp.error.ConfigurationError} at
         * {@link #build()} time if the major version does not match the SDK.
         */
        public Builder adcpVersion(String releaseVersion) {
            return adcpVersion(AdcpVersion.of(releaseVersion));
        }

        /** Override the Jackson ObjectMapper. */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper);
            return this;
        }

        /**
         * Override the SSRF policy. Defaults to {@link SsrfPolicy#strict()}.
         * Use {@link SsrfPolicy#permissive()} for local development only.
         */
        public Builder ssrfPolicy(SsrfPolicy ssrfPolicy) {
            this.ssrfPolicy = Objects.requireNonNull(ssrfPolicy);
            return this;
        }

        /** Builds the client. */
        public AdcpClient build() {
            validateAdcpVersion(adcpVersion);
            return new AdcpClient(this);
        }

        /**
         * Validates that the pinned version's major matches the SDK's built-in major.
         * Cross-major pins (e.g. requesting "2.0" from a major-3 SDK) fail fast before
         * any network request.
         */
        private static void validateAdcpVersion(@Nullable AdcpVersion version) {
            if (version == null) return;
            if (version.majorVersion() != AdcpSdkVersion.SDK_MAJOR_VERSION) {
                throw new ConfigurationError(
                        "adcpVersion major " + version.majorVersion()
                                + " does not match SDK major "
                                + AdcpSdkVersion.SDK_MAJOR_VERSION
                                + " (built for AdCP " + AdcpSdkVersion.SDK_RELEASE_VERSION + ")",
                        "adcpVersion");
            }
        }
    }
}
