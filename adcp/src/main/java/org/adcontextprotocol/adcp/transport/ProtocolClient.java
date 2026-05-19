package org.adcontextprotocol.adcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.adcontextprotocol.adcp.AdcpVersion;
import org.adcontextprotocol.adcp.AgentConfig;
import org.adcontextprotocol.adcp.Protocol;
import org.adcontextprotocol.adcp.auth.AuthTokenResolver;
import org.adcontextprotocol.adcp.error.FeatureUnsupportedError;
import org.adcontextprotocol.adcp.error.ProtocolError;
import org.adcontextprotocol.adcp.http.SsrfPolicy;
import org.adcontextprotocol.adcp.transport.mcp.McpCaller;
import org.adcontextprotocol.adcp.transport.mcp.McpConnectionManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches tool calls to the appropriate transport (MCP or A2A).
 *
 * <p>This is the central dispatch point — all named tool methods in
 * {@code AdcpClient} funnel through here. Mirrors the TS SDK's
 * {@code ProtocolClient.callTool()}.
 */
public final class ProtocolClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProtocolClient.class);

    private final McpConnectionManager connectionManager;
    private final McpCaller mcpCaller;
    private final SsrfPolicy ssrfPolicy;
    private final @Nullable AdcpVersion adcpVersion;

    /**
     * Creates a new protocol client.
     *
     * @param objectMapper       Jackson ObjectMapper for serialization
     * @param ssrfPolicy         SSRF policy for URL validation
     * @param adcpVersion        protocol version for the version envelope
     * @param connectionManager  MCP connection manager (shared)
     */
    public ProtocolClient(ObjectMapper objectMapper, SsrfPolicy ssrfPolicy,
                          @Nullable AdcpVersion adcpVersion,
                          McpConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.mcpCaller = new McpCaller(objectMapper);
        this.ssrfPolicy = ssrfPolicy;
        this.adcpVersion = adcpVersion;
    }

    /**
     * Calls a tool on the given agent.
     *
     * @param agent        the agent configuration
     * @param toolName     the tool name (e.g. "get_products")
     * @param args         tool arguments (caller-supplied)
     * @param responseType expected response type
     * @param options      call options (timeout, validation, etc.)
     * @param <T>          response type
     * @return the deserialized response
     */
    public <T> T callTool(AgentConfig agent, String toolName,
                          Map<String, Object> args, Class<T> responseType,
                          CallToolOptions options) {

        // 1. Validate agent URL against SSRF policy
        validateUrl(agent);

        // 2. Resolve auth headers
        Map<String, String> authHeaders = AuthTokenResolver.resolve(agent);

        // 3. Merge extra headers
        Map<String, String> allHeaders = new LinkedHashMap<>(authHeaders);
        allHeaders.putAll(agent.extraHeaders());

        // 4. Build version envelope and merge into args
        AdcpVersion version = agent.adcpVersion() != null ? agent.adcpVersion() : adcpVersion;
        Map<String, Object> mergedArgs = VersionEnvelope.mergeInto(args, version);

        // 5. Dispatch to transport
        return switch (agent.protocol()) {
            case MCP -> callViaMcp(agent, toolName, mergedArgs, allHeaders, responseType);
            case A2A -> throw new FeatureUnsupportedError(
                    List.of("A2A transport"),
                    List.of("MCP"));
        };
    }

    /**
     * Convenience: calls a tool with default options.
     */
    public <T> T callTool(AgentConfig agent, String toolName,
                          Map<String, Object> args, Class<T> responseType) {
        return callTool(agent, toolName, args, responseType, CallToolOptions.DEFAULT);
    }

    @Override
    public void close() {
        connectionManager.close();
    }

    private <T> T callViaMcp(AgentConfig agent, String toolName,
                             Map<String, Object> mergedArgs,
                             Map<String, String> headers,
                             Class<T> responseType) {
        String tokenHash = computeTokenHash(agent);
        McpSyncClient client = connectionManager.getOrConnect(
                agent.agentUri(), headers, tokenHash);

        try {
            return mcpCaller.callTool(client, toolName, mergedArgs, responseType);
        } catch (ProtocolError e) {
            if (!isTransportError(e)) {
                throw e;
            }
            // On transport error, evict and retry once
            connectionManager.evict(agent.agentUri(), tokenHash);
            log.debug("MCP transport error for {}, retrying after evict: {}",
                    toolName, e.getMessage());

            ProtocolError original = e;
            client = connectionManager.getOrConnect(
                    agent.agentUri(), headers, tokenHash);
            try {
                return mcpCaller.callTool(client, toolName, mergedArgs, responseType);
            } catch (ProtocolError retry) {
                retry.addSuppressed(original);
                throw retry;
            }
        }
    }

    private boolean isTransportError(ProtocolError e) {
        Throwable cause = e.getCause();
        return cause instanceof java.io.IOException
                || cause instanceof java.net.http.HttpTimeoutException
                || (cause != null && cause.getClass().getName().contains("Transport"));
    }

    private void validateUrl(AgentConfig agent) {
        String host = agent.agentUri().getHost();
        if (host == null) {
            throw new ProtocolError("mcp",
                    "Agent URI has no host: " + agent.agentUri(), null);
        }
        // Note: Full SSRF validation with DNS resolution is performed by
        // AdcpHttpClient.pinUri() on actual HTTP calls. MCP transport uses
        // its own HttpClient, so this validation is best-effort for the
        // initial hostname check. The MCP transport builder also gets
        // followRedirects(NEVER) set by McpConnectionManager.
    }

    /**
     * Computes a SHA-256 hash of the agent's credentials for use as a
     * cache key component.
     */
    static String computeTokenHash(AgentConfig agent) {
        String token = "";
        if (agent.authToken() != null) {
            token = agent.authToken();
        } else if (agent.oauthTokens() != null) {
            token = agent.oauthTokens().accessToken();
        } else if (agent.basicAuth() != null) {
            token = agent.basicAuth().username() + ":" + agent.basicAuth().password();
        }
        if (token.isEmpty()) {
            return "anonymous";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JRE; this should never happen
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
