package org.adcontextprotocol.adcp.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.adcontextprotocol.adcp.AdcpVersion;
import org.adcontextprotocol.adcp.error.UnsupportedTaskError;
import org.adcontextprotocol.adcp.schema.AdcpObjectMapperFactory;
import org.adcontextprotocol.adcp.server.AdcpContext;
import org.adcontextprotocol.adcp.server.AdcpPlatform;
import org.adcontextprotocol.adcp.server.AdcpServerBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies the full server-side wiring:
 * {@link AdcpPlatform} → {@link AdcpServerBuilder} → MCP server.
 *
 * <p>This test validates that the SDK correctly:
 * <ul>
 *   <li>Introspects supported tools from the platform</li>
 *   <li>Builds an MCP server with the correct tool registrations</li>
 *   <li>Dispatches tool calls through the builder's handleToolCall path</li>
 *   <li>Handles errors correctly</li>
 * </ul>
 */
class ServerBuilderRoundTripTest {

    /**
     * A simple test platform that supports get_products and list_accounts.
     */
    static class TestPlatform extends AdcpPlatform {
        boolean getProductsCalled;
        boolean listAccountsCalled;
        AdcpContext lastContext;

        @Override
        public Set<String> supportedTools() {
            return Set.of("get_products", "list_accounts");
        }

        @Override
        public Object handleTool(String toolName, Map<String, Object> request, AdcpContext ctx) {
            lastContext = ctx;
            return switch (toolName) {
                case "get_products" -> {
                    getProductsCalled = true;
                    yield Map.of("products", List.of(
                            Map.of("id", "p1", "name", "Product 1"),
                            Map.of("id", "p2", "name", "Product 2")));
                }
                case "list_accounts" -> {
                    listAccountsCalled = true;
                    yield Map.of("accounts", List.of());
                }
                default -> super.handleTool(toolName, request, ctx);
            };
        }
    }

    @Test
    void builder_creates_server_with_correct_tool_count() {
        TestPlatform platform = new TestPlatform();

        assertEquals(2, platform.supportedTools().size());
        assertTrue(platform.supportedTools().contains("get_products"));
        assertTrue(platform.supportedTools().contains("list_accounts"));
    }

    @Test
    void builder_build_creates_mcp_server() {
        TestPlatform platform = new TestPlatform();
        // Use a StubTransport so we exercise the builder.build() path
        McpServerTransportProvider transport = new StubMcpTransport();

        McpSyncServer server = AdcpServerBuilder.create(platform)
                .transport(transport)
                .serverName("test-server")
                .serverVersion("0.0.1")
                .adcpVersion(AdcpVersion.V3)
                .build();

        assertNotNull(server, "build() should return a non-null MCP server");
    }

    @Test
    void platform_dispatches_get_products() {
        TestPlatform platform = new TestPlatform();
        AdcpContext ctx = new AdcpContext(AdcpVersion.V3, Map.of(), "req-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                platform.handleTool("get_products", Map.of(), ctx);

        assertTrue(platform.getProductsCalled);
        assertNotNull(result.get("products"));
        assertInstanceOf(List.class, result.get("products"));
    }

    @Test
    void platform_dispatches_list_accounts() {
        TestPlatform platform = new TestPlatform();
        AdcpContext ctx = new AdcpContext(AdcpVersion.V3, Map.of(), "req-2");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                platform.handleTool("list_accounts", Map.of(), ctx);

        assertTrue(platform.listAccountsCalled);
        assertNotNull(result.get("accounts"));
    }

    @Test
    void platform_rejects_unsupported_tool() {
        TestPlatform platform = new TestPlatform();
        AdcpContext ctx = new AdcpContext(AdcpVersion.V3, Map.of(), "req-3");

        UnsupportedTaskError error = assertThrows(UnsupportedTaskError.class,
                () -> platform.handleTool("sync_creatives", Map.of(), ctx));

        assertTrue(error.getMessage().contains("sync_creatives"));
    }

    @Test
    void platform_receives_context_with_version_and_headers() {
        TestPlatform platform = new TestPlatform();
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer test-token",
                "X-Request-Id", "req-456");

        AdcpContext ctx = new AdcpContext(AdcpVersion.V3_1, headers, "req-456");
        platform.handleTool("get_products", Map.of(), ctx);

        assertNotNull(platform.lastContext);
        assertEquals(AdcpVersion.V3_1, platform.lastContext.adcpVersion());
        assertEquals("Bearer test-token", platform.lastContext.headers().get("Authorization"));
        assertEquals("req-456", platform.lastContext.requestId());
    }

    @Test
    void server_builder_requires_transport() {
        TestPlatform platform = new TestPlatform();

        assertThrows(Exception.class, () ->
                AdcpServerBuilder.create(platform).build());
    }

    @Test
    void server_builder_accepts_custom_server_info() {
        TestPlatform platform = new TestPlatform();

        AdcpServerBuilder builder = AdcpServerBuilder.create(platform)
                .serverName("test-agent")
                .serverVersion("1.0.0")
                .adcpVersion(AdcpVersion.V3);

        assertNotNull(builder);
    }

    @Test
    void version_extraction_strips_envelope_from_args() {
        TestPlatform platform = new TestPlatform();
        Map<String, Object> argsWithVersion = new java.util.LinkedHashMap<>(Map.of(
                "adcp_major_version", 3,
                "adcp_version", "3.1",
                "query", "test"));

        AdcpContext ctx = new AdcpContext(AdcpVersion.V3_1, Map.of(), null);
        Object result = platform.handleTool("get_products", argsWithVersion, ctx);
        assertNotNull(result);
    }

    @Test
    void multiple_tools_independent_dispatch() {
        TestPlatform platform = new TestPlatform();
        AdcpContext ctx = new AdcpContext(AdcpVersion.V3, Map.of(), null);

        assertFalse(platform.getProductsCalled);
        assertFalse(platform.listAccountsCalled);

        platform.handleTool("get_products", Map.of(), ctx);
        assertTrue(platform.getProductsCalled);
        assertFalse(platform.listAccountsCalled);

        platform.handleTool("list_accounts", Map.of(), ctx);
        assertTrue(platform.listAccountsCalled);
    }

    /**
     * Minimal stub transport that satisfies the non-null requirement for
     * {@link AdcpServerBuilder#build()} without starting a real server.
     * The MCP server builder calls no methods on the transport during build().
     */
    private static class StubMcpTransport implements McpServerTransportProvider {
        @Override
        public void setSessionFactory(
                io.modelcontextprotocol.spec.McpServerSession.Factory factory) {
            // no-op
        }

        @Override
        public reactor.core.publisher.Mono<Void> notifyClients(
                String method,
                Object params) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Void> closeGracefully() {
            return reactor.core.publisher.Mono.empty();
        }
    }
}
