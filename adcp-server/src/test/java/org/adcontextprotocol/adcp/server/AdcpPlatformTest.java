package org.adcontextprotocol.adcp.server;

import org.adcontextprotocol.adcp.AdcpVersion;
import org.adcontextprotocol.adcp.error.UnsupportedTaskError;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdcpPlatform} and {@link AdcpContext}.
 */
class AdcpPlatformTest {

    @Test
    void default_handleTool_throws_unsupported() {
        AdcpPlatform platform = new AdcpPlatform() {
            @Override
            public Set<String> supportedTools() {
                return Set.of();
            }
        };

        AdcpContext ctx = new AdcpContext(AdcpVersion.V3, Map.of(), null);

        assertThrows(UnsupportedTaskError.class,
                () -> platform.handleTool("get_products", Map.of(), ctx));
    }

    @Test
    void custom_platform_handles_tool() {
        AdcpPlatform platform = new AdcpPlatform() {
            @Override
            public Set<String> supportedTools() {
                return Set.of("get_products");
            }

            @Override
            public Object handleTool(String toolName, Object request, AdcpContext ctx) {
                if ("get_products".equals(toolName)) {
                    return Map.of("products", java.util.List.of());
                }
                return super.handleTool(toolName, request, ctx);
            }
        };

        AdcpContext ctx = new AdcpContext(AdcpVersion.V3, Map.of(), "req-1");
        Object result = platform.handleTool("get_products", Map.of(), ctx);

        assertNotNull(result);
        assertInstanceOf(Map.class, result);
    }

    @Test
    void context_headers_are_immutable() {
        var headers = new java.util.HashMap<String, String>();
        headers.put("Authorization", "Bearer tok");

        AdcpContext ctx = new AdcpContext(AdcpVersion.V3, headers, null);

        assertThrows(UnsupportedOperationException.class,
                () -> ctx.headers().put("X-Evil", "val"));
    }

    @Test
    void context_records_request_id() {
        AdcpContext ctx = new AdcpContext(null, Map.of(), "req-123");
        assertEquals("req-123", ctx.requestId());
    }
}
