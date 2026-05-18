package org.adcontextprotocol.adcp.transport.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.adcontextprotocol.adcp.error.AuthenticationRequiredError;
import org.adcontextprotocol.adcp.error.ProtocolError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Manages cached MCP client connections with LRU eviction.
 *
 * <p>Cache key: {@code agentUrl::tokenHash}. Max 20 entries.
 * Implements StreamableHTTP → SSE fallback per TS SDK behavior.
 */
public final class McpConnectionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);
    private static final int MAX_CACHE_SIZE = 20;

    private final ConcurrentHashMap<String, McpSyncClient> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> accessOrder = new ConcurrentLinkedDeque<>();
    private final Set<String> knownStreamableUrls = ConcurrentHashMap.newKeySet();
    private final Duration connectTimeout;

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
     * retries once, then falls back to SSE for unknown endpoints.
     * On 401, throws {@link AuthenticationRequiredError} immediately.
     *
     * @param agentUri  the agent's base URI
     * @param headers   auth + extra headers
     * @param tokenHash hash of the auth token (for cache keying)
     * @return a connected {@link McpSyncClient}
     */
    public McpSyncClient getOrConnect(URI agentUri, Map<String, String> headers,
                                      String tokenHash) {
        String cacheKey = agentUri + "::" + tokenHash;

        // Touch access order
        accessOrder.remove(cacheKey);
        accessOrder.addFirst(cacheKey);

        McpSyncClient existing = cache.get(cacheKey);
        if (existing != null) {
            return existing;
        }

        McpSyncClient client = connectWithFallback(agentUri, headers);
        cache.put(cacheKey, client);

        // Evict oldest if over capacity
        while (cache.size() > MAX_CACHE_SIZE) {
            String oldest = accessOrder.pollLast();
            if (oldest != null) {
                McpSyncClient evicted = cache.remove(oldest);
                closeQuietly(evicted);
            }
        }

        return client;
    }

    /**
     * Evicts a specific connection from the cache.
     */
    public void evict(URI agentUri, String tokenHash) {
        String cacheKey = agentUri + "::" + tokenHash;
        McpSyncClient evicted = cache.remove(cacheKey);
        accessOrder.remove(cacheKey);
        if (evicted != null) {
            closeQuietly(evicted);
        }
    }

    @Override
    public void close() {
        cache.values().forEach(this::closeQuietly);
        cache.clear();
        accessOrder.clear();
    }

    private McpSyncClient connectWithFallback(URI agentUri, Map<String, String> headers) {
        String url = agentUri.toString();

        // Try StreamableHTTP first
        try {
            McpClientTransport transport = HttpClientStreamableHttpTransport.builder(url)
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .build();
            client.initialize();
            knownStreamableUrls.add(url);
            log.debug("Connected to {} via StreamableHTTP", agentUri);
            return client;
        } catch (Exception e) {
            if (isAuthError(e)) {
                throw new AuthenticationRequiredError(agentUri, null, null);
            }
            log.debug("StreamableHTTP failed for {}: {}", agentUri, e.getMessage());
        }

        // If this URL has never succeeded with StreamableHTTP, try SSE fallback
        if (!knownStreamableUrls.contains(url)) {
            try {
                McpClientTransport transport = HttpClientSseClientTransport.builder(url)
                        .build();
                McpSyncClient client = McpClient.sync(transport)
                        .build();
                client.initialize();
                log.debug("Connected to {} via SSE fallback", agentUri);
                return client;
            } catch (Exception e) {
                if (isAuthError(e)) {
                    throw new AuthenticationRequiredError(agentUri, null, null);
                }
                throw new ProtocolError("mcp",
                        "Failed to connect to " + agentUri + " via StreamableHTTP and SSE",
                        e);
            }
        }

        // Retry StreamableHTTP once for known-good endpoints
        try {
            McpClientTransport transport = HttpClientStreamableHttpTransport.builder(url)
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .build();
            client.initialize();
            log.debug("Reconnected to {} via StreamableHTTP (retry)", agentUri);
            return client;
        } catch (Exception e) {
            throw new ProtocolError("mcp",
                    "Failed to reconnect to " + agentUri + " via StreamableHTTP",
                    e);
        }
    }

    private boolean isAuthError(Exception e) {
        // Check for 401 status in the exception chain
        String msg = e.getMessage();
        return msg != null && (msg.contains("401") || msg.contains("Unauthorized"));
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
