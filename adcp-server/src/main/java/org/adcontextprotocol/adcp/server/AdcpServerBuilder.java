package org.adcontextprotocol.adcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.adcontextprotocol.adcp.AdcpVersion;
import org.adcontextprotocol.adcp.error.ProtocolError;
import org.adcontextprotocol.adcp.schema.AdcpObjectMapperFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds and wires an MCP server backed by an {@link AdcpPlatform}.
 *
 * <p>Introspects the platform's {@link AdcpPlatform#supportedTools()} to
 * register MCP tool handlers. Only supported tools are advertised via
 * {@code tools/list}.
 *
 * <p>Usage:
 * <pre>{@code
 * McpServerTransportProvider transport = ...;
 * AdcpServerBuilder.create(myPlatform)
 *     .transport(transport)
 *     .build()
 *     .initialize();
 * }</pre>
 */
public final class AdcpServerBuilder {

    private static final Logger log = LoggerFactory.getLogger(AdcpServerBuilder.class);

    private final AdcpPlatform platform;
    private @Nullable McpServerTransportProvider transport;
    private @Nullable ObjectMapper objectMapper;
    private @Nullable AdcpVersion adcpVersion;
    private String serverName = "adcp-java-sdk";
    private String serverVersion = "0.1.0";

    private AdcpServerBuilder(AdcpPlatform platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    /** Creates a new server builder for the given platform. */
    public static AdcpServerBuilder create(AdcpPlatform platform) {
        return new AdcpServerBuilder(platform);
    }

    /** Sets the MCP transport provider (required). */
    public AdcpServerBuilder transport(McpServerTransportProvider transport) {
        this.transport = Objects.requireNonNull(transport);
        return this;
    }

    /** Overrides the Jackson ObjectMapper. */
    public AdcpServerBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        return this;
    }

    /** Sets the advertised server name. */
    public AdcpServerBuilder serverName(String serverName) {
        this.serverName = Objects.requireNonNull(serverName);
        return this;
    }

    /** Sets the advertised server version. */
    public AdcpServerBuilder serverVersion(String serverVersion) {
        this.serverVersion = Objects.requireNonNull(serverVersion);
        return this;
    }

    /** Sets the AdCP version for response envelopes. */
    public AdcpServerBuilder adcpVersion(AdcpVersion adcpVersion) {
        this.adcpVersion = adcpVersion;
        return this;
    }

    /**
     * Builds and returns the MCP server. Call {@code initialize()} on the
     * result to start accepting connections.
     */
    public McpSyncServer build() {
        if (transport == null) {
            throw new ProtocolError("mcp",
                    "McpServerTransportProvider is required", null);
        }

        ObjectMapper om = objectMapper != null
                ? objectMapper
                : AdcpObjectMapperFactory.create();

        Set<String> tools = platform.supportedTools();
        Map<String, String> descriptions = platform.toolDescriptions();
        log.info("Building AdCP server with {} tool(s): {}", tools.size(), tools);

        // Build the MCP server with tool handlers
        var spec = McpServer.sync(transport)
                .serverInfo(serverName, serverVersion);

        for (String toolName : tools) {
            String description = descriptions.getOrDefault(toolName, toolName);
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(toolName)
                    .description(description)
                    .build();
            spec.toolCall(tool,
                    (exchange, request) -> handleToolCall(om, toolName, request));
        }

        return spec.build();
    }

    @SuppressWarnings("unchecked")
    private McpSchema.CallToolResult handleToolCall(
            ObjectMapper om, String toolName, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = request.arguments() != null
                    ? new java.util.LinkedHashMap<>(request.arguments())
                    : new java.util.LinkedHashMap<>();

            AdcpVersion version = extractVersion(args);

            // Strip version envelope fields before passing to platform
            args.remove("adcp_major_version");
            args.remove("adcp_version");

            AdcpContext ctx = new AdcpContext(version, Map.of(), null);

            Object response = platform.handleTool(toolName, args, ctx);

            String json = om.writeValueAsString(response);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(json)),
                    false, null, Map.of());
        } catch (org.adcontextprotocol.adcp.error.AdcpError e) {
            // Known application errors — safe to surface the code and message
            log.warn("Tool call failed ({}): {}", toolName, e.code());
            String safeError;
            try {
                safeError = om.writeValueAsString(
                        Map.of("error", e.getMessage(), "code", e.code()));
            } catch (Exception ignored) {
                // e.code() is always an enum-like constant, but use a
                // fixed string to be absolutely safe against JSON injection.
                safeError = "{\"error\":\"internal_error\"}";
            }
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(safeError)),
                    true, null, Map.of());
        } catch (Exception e) {
            // Unknown errors — do NOT leak internal details to remote callers
            log.error("Tool call failed: {}", toolName, e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("{\"error\":\"internal error\"}")),
                    true, null, Map.of());
        }
    }

    private @Nullable AdcpVersion extractVersion(Map<String, Object> args) {
        Object majorRaw = args.get("adcp_major_version");
        if (majorRaw instanceof Number num) {
            int major = num.intValue();
            if (major < 3) {
                throw new org.adcontextprotocol.adcp.error.VersionUnsupportedError(
                        null, "Unsupported AdCP major version: " + major,
                        String.valueOf(major), null);
            }
            String minor = args.get("adcp_version") instanceof String s ? s : null;
            return new AdcpVersion(major, minor);
        }
        return adcpVersion;
    }
}
