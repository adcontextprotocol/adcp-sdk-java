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
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

        // 1. Check protocol support early so unsupported transports fail fast
        if (agent.protocol() == org.adcontextprotocol.adcp.Protocol.A2A) {
            throw new FeatureUnsupportedError(
                    List.of("A2A transport"),
                    List.of("MCP"));
        }

        // 2. Validate agent URL against SSRF policy
        validateUrl(agent);

        // 2. Warn if non-default options are passed (not yet enforced in v0.1)
        if (!options.equals(CallToolOptions.DEFAULT)) {
            log.debug("CallToolOptions fields are not yet enforced by the MCP transport (v0.1)");
        }

        // 3. Resolve auth headers
        Map<String, String> authHeaders = AuthTokenResolver.resolve(agent);

        // 4. Merge headers: filter extraHeaders through ProtectedHeaders first
        // (so callers cannot override authorization/cookie/etc.), then add
        // SDK-resolved auth headers which are trusted and must not be filtered.
        Map<String, String> allHeaders = new LinkedHashMap<>();
        agent.extraHeaders().forEach((name, value) -> {
            if (!org.adcontextprotocol.adcp.http.ProtectedHeaders.isProtected(name)) {
                allHeaders.put(name, value);
            } else {
                log.debug("Dropping protected extraHeader: {}", name);
            }
        });
        allHeaders.putAll(authHeaders);

        // 5. Build version envelope and merge into args
        AdcpVersion version = agent.adcpVersion() != null ? agent.adcpVersion() : adcpVersion;
        Map<String, Object> mergedArgs = VersionEnvelope.mergeInto(args, version);

        // 6. Dispatch to transport (A2A already rejected in step 1)
        return callViaMcp(agent, toolName, mergedArgs, allHeaders, responseType);
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
        // Walk the full cause chain — any I/O or timeout failure is transient
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof java.io.IOException
                    || t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private void validateUrl(AgentConfig agent) {
        String scheme = agent.agentUri().getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new ProtocolError("mcp",
                    "Agent URI scheme must be http or https: " + agent.agentUri(), null);
        }
        String host = agent.agentUri().getHost();
        if (host == null) {
            throw new ProtocolError("mcp",
                    "Agent URI has no host: " + agent.agentUri(), null);
        }
        // Resolve DNS and validate all addresses against SSRF policy.
        // Probes are routed through AdcpHttpClient (which re-validates),
        // but the MCP transport's underlying HttpClient still re-resolves
        // DNS independently (TOCTOU limitation). This early check blocks
        // the common case of misconfigured URIs pointing at private addresses.
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
     * Per-process HMAC key — prevents token hash reversibility in heap dumps.
     * Generated once at class-load time; never persisted.
     */
    private static final byte[] HMAC_KEY;
    static {
        HMAC_KEY = new byte[32];
        new SecureRandom().nextBytes(HMAC_KEY);
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
        Mac mac = createHmac();
        mac.update(tokenHash.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '\0');
        agent.extraHeaders().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    mac.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                    mac.update((byte) '=');
                    mac.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                    mac.update((byte) '\n');
                });
        return HexFormat.of().formatHex(mac.doFinal());
    }

    /**
     * Computes an HMAC-SHA256 of the agent's credentials for use as a
     * cache key component. The per-process random HMAC key prevents
     * reversal of known token formats (e.g. {@code ghp_*}) from heap dumps.
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
            token = "cc:" + agent.oauthClientCredentials().clientId();
        }
        if (token.isEmpty()) {
            return "anonymous";
        }
        Mac mac = createHmac();
        return HexFormat.of().formatHex(
                mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static Mac createHmac() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY, "HmacSHA256"));
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new AssertionError("HmacSHA256 not available", e);
        }
    }
}
