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

        // 2. Warn if non-default options are passed (not yet enforced in v0.1)
        if (!options.equals(CallToolOptions.DEFAULT)) {
            log.debug("CallToolOptions fields are not yet enforced by the MCP transport (v0.1)");
        }

        // 3. Resolve auth headers
        Map<String, String> authHeaders = AuthTokenResolver.resolve(agent);

        // 4. Merge headers: extra headers first, then auth (auth wins)
        Map<String, String> allHeaders = new LinkedHashMap<>(agent.extraHeaders());
        allHeaders.putAll(authHeaders);

        // 5. Build version envelope and merge into args
        AdcpVersion version = agent.adcpVersion() != null ? agent.adcpVersion() : adcpVersion;
        Map<String, Object> mergedArgs = VersionEnvelope.mergeInto(args, version);

        // 6. Dispatch to transport
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
        String cacheHash = computeCacheHash(agent);
        McpSyncClient client = connectionManager.getOrConnect(
                agent.agentUri(), headers, cacheHash);

        try {
            return mcpCaller.callTool(client, toolName, mergedArgs, responseType);
        } catch (ProtocolError e) {
            if (!isTransportError(e)) {
                throw e;
            }
            // On transport error, evict and retry once
            connectionManager.evict(agent.agentUri(), cacheHash);
            log.debug("MCP transport error for {}, retrying after evict: {}",
                    toolName, e.getMessage());

            ProtocolError original = e;
            client = connectionManager.getOrConnect(
                    agent.agentUri(), headers, cacheHash);
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
        // Resolve DNS and validate all addresses against SSRF policy.
        // Note: The MCP transport uses its own HttpClient which re-resolves
        // DNS independently (TOCTOU limitation), but this check blocks the
        // common case of misconfigured URIs pointing at private addresses.
        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress addr : addresses) {
                org.adcontextprotocol.adcp.http.DnsPinResolver.validateAddress(
                        addr, ssrfPolicy);
            }
        } catch (org.adcontextprotocol.adcp.http.SsrfBlockedException e) {
            throw new ProtocolError("mcp",
                    "Agent URI blocked by SSRF policy", e);
        } catch (java.net.UnknownHostException e) {
            throw new ProtocolError("mcp",
                    "Cannot resolve agent host", e);
        }
    }

    /**
     * Computes a combined hash of credentials + extraHeaders for use as
     * a connection cache key. This ensures connections are not shared
     * across different auth tokens or different routing headers.
     */
    private static String computeCacheHash(AgentConfig agent) {
        String tokenHash = computeTokenHash(agent);
        if (agent.extraHeaders().isEmpty()) {
            return tokenHash;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(tokenHash.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\0');
            agent.extraHeaders().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                        md.update((byte) '=');
                        md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                        md.update((byte) '\n');
                    });
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
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
        } else if (agent.oauthClientCredentials() != null) {
            // Client-credentials flow: key on clientId to distinguish
            // different OAuth apps hitting the same endpoint.
            token = "cc:" + agent.oauthClientCredentials().clientId();
        }
        if (token.isEmpty()) {
            return "anonymous";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JRE; this should never happen
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
