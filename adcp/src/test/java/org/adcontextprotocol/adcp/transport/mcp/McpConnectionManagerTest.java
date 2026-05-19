package org.adcontextprotocol.adcp.transport.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpConnectionManager}.
 *
 * <p>These tests verify the cache management, eviction, and lifecycle
 * behavior without making real MCP connections (which require a running
 * MCP server).
 */
class McpConnectionManagerTest {

    private final McpConnectionManager manager = new McpConnectionManager();

    @AfterEach
    void cleanup() {
        manager.close();
    }

    @Test
    void close_clears_cache() {
        // Just verify close doesn't throw on empty cache
        assertDoesNotThrow(manager::close);
    }

    @Test
    void implements_autocloseable() {
        // Verify the manager can be used in try-with-resources
        try (McpConnectionManager mgr = new McpConnectionManager()) {
            assertNotNull(mgr);
        }
    }

    @Test
    void evict_nonexistent_is_noop() {
        var uri = java.net.URI.create("https://agent.example.com");
        assertDoesNotThrow(() -> manager.evict(uri, "abc"));
    }

    @Test
    void getOrConnect_after_close_throws() {
        manager.close();
        var uri = java.net.URI.create("https://agent.example.com");
        assertThrows(IllegalStateException.class,
                () -> manager.getOrConnect(uri, java.util.Map.of(), "hash"));
    }

    @Test
    void double_close_is_safe() {
        manager.close();
        assertDoesNotThrow(manager::close);
    }
}
