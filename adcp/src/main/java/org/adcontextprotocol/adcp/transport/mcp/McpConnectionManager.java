package org.adcontextprotocol.adcp.transport.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import org.adcontextprotocol.adcp.error.AuthenticationRequiredError;
import org.adcontextprotocol.adcp.error.ProtocolError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages cached MCP client connections with LRU eviction.
 *
 * <p>Cache key: {@code agentUrl::tokenHash}. Max 20 entries.
 * Implements StreamableHTTP → SSE fallback per TS SDK behavior.
 *
 * <p>Thread-safe: all cache operations are protected by an explicit
 * lock. The lock is held during connection establishment (blocking
 * network call) to prevent duplicate connections for the same key.
 */
public final class McpConnectionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);
    static final int MAX_CACHE_SIZE = 20;

    private final LinkedHashMap<String, McpSyncClient> cache =
            new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock lock = new ReentrantLock();
    private final java.util.HashSet<String> knownStreamableKeys = new java.util.HashSet<>();
    private final Duration connectTimeout;
    private volatile boolean closed;

    public McpConnectionManager() {
        this(Duration.ofSeconds(10));
    }

    public McpConnectionManager(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * Gets or creates a cached MCP client connection.
     *
     * <p>On first connect, tries StreamableHTTP first. On non-401 failure,
     * falls back to SSE for unknown endpoints.
     * On 401, throws {@link AuthenticationRequiredError} immediately.
     *
     * @param agentUri  the agent's base URI
     * @param headers   auth + extra headers to inject into MCP requests
     * @param tokenHash hash of the auth token (for cache keying)
     * @return a connected {@link McpSyncClient}
     * @throws IllegalStateException if the manager has been closed
     */
    public McpSyncClient getOrConnect(URI agentUri, Map<String, String> headers,
                                       String tokenHash) {
        String cacheKey = agentUri + "::" + tokenHash;
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("McpConnectionManager is closed");
            }

            McpSyncClient existing = cache.get(cacheKey);
            if (existing != null) {
                return existing;
            }

            McpSyncClient client = connectWithFallback(agentUri, headers, cacheKey);
            cache.put(cacheKey, client);
            evictOldest();
            return client;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evicts a specific connection from the cache.
     */
    public void evict(URI agentUri, String tokenHash) {
        String cacheKey = agentUri + "::" + tokenHash;
        lock.lock();
        try {
            McpSyncClient evicted = cache.remove(cacheKey);
            if (evicted != null) {
                knownStreamableKeys.remove(cacheKey);
                closeQuietly(evicted);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            cache.values().forEach(this::closeQuietly);
            cache.clear();
            knownStreamableKeys.clear();
        } finally {
            lock.unlock();
        }
    }

    private void evictOldest() {
        while (cache.size() > MAX_CACHE_SIZE) {
            var it = cache.entrySet().iterator();
            if (it.hasNext()) {
                var entry = it.next();
                it.remove();
                knownStreamableKeys.remove(entry.getKey());
                closeQuietly(entry.getValue());
            }
        }
    }

    private McpSyncClient connectWithFallback(URI agentUri, Map<String, String> headers,
                                               String cacheKey) {
        String url = agentUri.toString();
        Map<String, String> safe = sanitizeHeaders(headers);

        // Try StreamableHTTP first
        try {
            McpSyncClient client = buildAndInit(url, safe, true);
            knownStreamableKeys.add(cacheKey);
            log.debug("Connected to {} via StreamableHTTP", agentUri);
            return client;
        } catch (Exception e) {
            if (isAuthError(e)) {
                throw new AuthenticationRequiredError(agentUri, null, null, e);
            }
            log.debug("StreamableHTTP failed for {}: {}", agentUri, e.getMessage());
        }

        // If this cache key has never succeeded with StreamableHTTP, try SSE fallback
        if (!knownStreamableKeys.contains(cacheKey)) {
            try {
                McpSyncClient client = buildAndInit(url, safe, false);
                log.debug("Connected to {} via SSE fallback", agentUri);
                return client;
            } catch (Exception e) {
                if (isAuthError(e)) {
                    throw new AuthenticationRequiredError(agentUri, null, null, e);
                }
                throw new ProtocolError("mcp",
                        "Failed to connect to " + agentUri
                                + " via StreamableHTTP and SSE",
                        e);
            }
        }

        // Retry StreamableHTTP once for known-good endpoints (after eviction/reconnect)
        try {
            McpSyncClient client = buildAndInit(url, safe, true);
            log.debug("Reconnected to {} via StreamableHTTP (retry)", agentUri);
            return client;
        } catch (Exception e) {
            throw new ProtocolError("mcp",
                    "Failed to reconnect to " + agentUri + " via StreamableHTTP",
                    e);
        }
    }

    private McpSyncClient buildAndInit(String url, Map<String, String> headers,
                                        boolean useStreamable) {
        McpClientTransport transport = useStreamable
                ? HttpClientStreamableHttpTransport.builder(url)
                        .connectTimeout(connectTimeout)
                        .customizeClient(cb -> cb.followRedirects(HttpClient.Redirect.NEVER))
                        .httpRequestCustomizer((rb, method, uri, body, ctx) ->
                                headers.forEach(rb::header))
                        .build()
                : HttpClientSseClientTransport.builder(url)
                        .connectTimeout(connectTimeout)
                        .customizeClient(cb -> cb.followRedirects(HttpClient.Redirect.NEVER))
                        .httpRequestCustomizer((rb, method, uri, body, ctx) ->
                                headers.forEach(rb::header))
                        .build();
        McpSyncClient client = McpClient.sync(transport).build();
        try {
            client.initialize();
            return client;
        } catch (Exception e) {
            closeQuietly(client);
            throw e;
        }
    }

    private static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (var entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (org.adcontextprotocol.adcp.http.ProtectedHeaders.isProtected(name)) {
                log.debug("Skipping protected MCP header: {}", name);
                continue;
            }
            if (hasCrlf(name) || hasCrlf(value)) {
                log.warn("Rejecting MCP header with CR/LF characters: {}", name);
                continue;
            }
            sanitized.put(name, value);
        }
        return sanitized;
    }

    private static boolean hasCrlf(String s) {
        return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
    }

    // TODO(7.2.0-delta): MCP SDK 1.1.2 does not expose HTTP response headers
    // on errors. When it does, parse WWW-Authenticate via WwwAuthenticateParser
    // and populate AuthenticationRequiredError.challenge(). Until then, callers
    // receive challenge=null on auth errors from the MCP path.
    private boolean isAuthError(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            // Check MCP SDK's error type first
            if (t instanceof McpError) {
                String msg = t.getMessage();
                if (msg != null && msg.contains("401")) return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("HTTP 401")
                    || msg.contains("status: 401")
                    || msg.contains("401 Unauthorized"))) {
                return true;
            }
        }
        return false;
    }

    private void closeQuietly(McpSyncClient client) {
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            log.debug("Error closing MCP client: {}", e.getMessage());
        }
    }
}
