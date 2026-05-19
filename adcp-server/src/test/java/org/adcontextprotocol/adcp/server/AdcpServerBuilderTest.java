package org.adcontextprotocol.adcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.adcontextprotocol.adcp.AdcpVersion;
import org.adcontextprotocol.adcp.schema.AdcpObjectMapperFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdcpServerBuilder}: handleToolCall dispatch, version
 * extraction/stripping, error wrapping, and back-compat behavior.
 *
 * <p>These tests exercise the builder's internal wiring directly — the
 * same code path that MCP tool calls follow at runtime.
 */
class AdcpServerBuilderTest {

    private final ObjectMapper om = AdcpObjectMapperFactory.create();

    static class EchoPlatform extends AdcpPlatform {
        Map<String, Object> lastArgs;
        AdcpContext lastContext;

        @Override
        public Set<String> supportedTools() {
            return Set.of("echo");
        }

        @Override
        public Object handleTool(String toolName, Map<String, Object> request, AdcpContext ctx) {
            lastArgs = request;
            lastContext = ctx;
            return Map.of("echo", request, "tool", toolName);
        }
    }

    private AdcpServerBuilder builderWith(AdcpPlatform platform) {
        return AdcpServerBuilder.create(platform).adcpVersion(AdcpVersion.V3);
    }

    // -- handleToolCall --

    @Test
    void handleToolCall_dispatches_and_returns_json_result() {
        EchoPlatform platform = new EchoPlatform();
        AdcpServerBuilder builder = builderWith(platform);

        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "echo", Map.of("query", "test"));

        McpSchema.CallToolResult result = builder.handleToolCall(om, "echo", request);

        assertFalse(result.isError());
        assertNotNull(result.content());
        assertFalse(result.content().isEmpty());
        assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst());
        String json = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertTrue(json.contains("\"echo\""), "Result should contain echo field: " + json);
        assertTrue(json.contains("\"query\""), "Result should contain query arg: " + json);
    }

    @Test
    void handleToolCall_strips_version_envelope_before_dispatch() {
        EchoPlatform platform = new EchoPlatform();
        AdcpServerBuilder builder = builderWith(platform);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("adcp_major_version", 3);
        args.put("adcp_version", "3.1");
        args.put("query", "test");

        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("echo", args);
        builder.handleToolCall(om, "echo", request);

        // Version fields should be stripped before reaching the platform
        assertNotNull(platform.lastArgs);
        assertFalse(platform.lastArgs.containsKey("adcp_major_version"),
                "adcp_major_version should be stripped");
        assertFalse(platform.lastArgs.containsKey("adcp_version"),
                "adcp_version should be stripped");
        assertEquals("test", platform.lastArgs.get("query"),
                "Non-envelope args should be preserved");
    }

    @Test
    void handleToolCall_extracts_version_into_context() {
        EchoPlatform platform = new EchoPlatform();
        AdcpServerBuilder builder = builderWith(platform);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("adcp_major_version", 3);
        args.put("adcp_version", "3.1");

        builder.handleToolCall(om, "echo",
                new McpSchema.CallToolRequest("echo", args));

        assertNotNull(platform.lastContext);
        assertNotNull(platform.lastContext.adcpVersion());
        assertEquals(3, platform.lastContext.adcpVersion().majorVersion());
        assertEquals("3.1", platform.lastContext.adcpVersion().minorVersion());
    }

    @Test
    void handleToolCall_wraps_adcp_errors() {
        AdcpPlatform failingPlatform = new AdcpPlatform() {
            @Override
            public Set<String> supportedTools() {
                return Set.of("fail");
            }

            @Override
            public Object handleTool(String toolName, Map<String, Object> request,
                                     AdcpContext ctx) {
                throw new org.adcontextprotocol.adcp.error.UnsupportedTaskError("fail");
            }
        };

        AdcpServerBuilder builder = builderWith(failingPlatform);
        McpSchema.CallToolResult result = builder.handleToolCall(om, "fail",
                new McpSchema.CallToolRequest("fail", Map.of()));

        assertTrue(result.isError(), "Should be marked as error");
        String errorJson = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertTrue(errorJson.contains("UNSUPPORTED_TASK"),
                "Error should contain stable error code: " + errorJson);
    }

    @Test
    void handleToolCall_wraps_unexpected_exceptions() {
        AdcpPlatform throwingPlatform = new AdcpPlatform() {
            @Override
            public Set<String> supportedTools() {
                return Set.of("boom");
            }

            @Override
            public Object handleTool(String toolName, Map<String, Object> request,
                                     AdcpContext ctx) {
                throw new RuntimeException("Unexpected error with sensitive details");
            }
        };

        AdcpServerBuilder builder = builderWith(throwingPlatform);
        McpSchema.CallToolResult result = builder.handleToolCall(om, "boom",
                new McpSchema.CallToolRequest("boom", Map.of()));

        assertTrue(result.isError());
        String errorJson = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertTrue(errorJson.contains("internal error"),
                "Unknown errors should be wrapped as internal error: " + errorJson);
        assertFalse(errorJson.contains("sensitive"),
                "Internal details must not leak: " + errorJson);
    }

    @Test
    void handleToolCall_handles_null_arguments() {
        EchoPlatform platform = new EchoPlatform();
        AdcpServerBuilder builder = builderWith(platform);

        McpSchema.CallToolResult result = builder.handleToolCall(om, "echo",
                new McpSchema.CallToolRequest("echo", null));

        assertFalse(result.isError());
        assertNotNull(platform.lastArgs);
        assertTrue(platform.lastArgs.isEmpty());
    }

    // -- extractVersion --

    @Test
    void extractVersion_parses_integer_major() {
        AdcpServerBuilder builder = builderWith(new EchoPlatform());
        Map<String, Object> args = new LinkedHashMap<>(
                Map.of("adcp_major_version", 3, "adcp_version", "3.1"));

        AdcpVersion version = builder.extractVersion(args);

        assertNotNull(version);
        assertEquals(3, version.majorVersion());
        assertEquals("3.1", version.minorVersion());
    }

    @Test
    void extractVersion_parses_string_major() {
        AdcpServerBuilder builder = builderWith(new EchoPlatform());
        Map<String, Object> args = new LinkedHashMap<>(
                Map.of("adcp_major_version", "3"));

        AdcpVersion version = builder.extractVersion(args);

        assertNotNull(version);
        assertEquals(3, version.majorVersion());
    }

    @Test
    void extractVersion_defaults_to_v1_semantics_for_major_lt_3() {
        AdcpServerBuilder builder = builderWith(new EchoPlatform());
        Map<String, Object> args = new LinkedHashMap<>(
                Map.of("adcp_major_version", 1));

        AdcpVersion version = builder.extractVersion(args);

        assertNotNull(version);
        assertEquals(1, version.majorVersion());
    }

    @Test
    void extractVersion_rejects_major_0() {
        AdcpServerBuilder builder = builderWith(new EchoPlatform());
        Map<String, Object> args = new LinkedHashMap<>(
                Map.of("adcp_major_version", 0));

        assertThrows(org.adcontextprotocol.adcp.error.VersionUnsupportedError.class,
                () -> builder.extractVersion(args));
    }

    @Test
    void extractVersion_rejects_major_100() {
        AdcpServerBuilder builder = builderWith(new EchoPlatform());
        Map<String, Object> args = new LinkedHashMap<>(
                Map.of("adcp_major_version", 100));

        assertThrows(org.adcontextprotocol.adcp.error.VersionUnsupportedError.class,
                () -> builder.extractVersion(args));
    }

    @Test
    void extractVersion_returns_default_when_no_version_field() {
        AdcpServerBuilder builder = builderWith(new EchoPlatform());

        AdcpVersion version = builder.extractVersion(Map.of("query", "test"));

        assertEquals(AdcpVersion.V3, version);
    }

    @Test
    void extractVersion_rejects_oversized_minor_version() {
        AdcpServerBuilder builder = builderWith(new EchoPlatform());
        Map<String, Object> args = new LinkedHashMap<>(
                Map.of("adcp_major_version", 3, "adcp_version", "x".repeat(100)));

        AdcpVersion version = builder.extractVersion(args);

        assertNotNull(version);
        assertEquals(3, version.majorVersion());
        assertNull(version.minorVersion(), "Oversized minor should be rejected");
    }
}
