package org.adcontextprotocol.adcp.server;

import org.adcontextprotocol.adcp.error.UnsupportedTaskError;

/**
 * Service Provider Interface for AdCP agent implementations.
 *
 * <p>Adopters extend this class and override the tools they support.
 * The SDK introspects which methods are overridden and only advertises
 * those tools via MCP {@code tools/list}. Unoverridden methods throw
 * {@link UnsupportedTaskError}.
 *
 * <p>Each method receives a typed request and an {@link AdcpContext}
 * with per-request metadata (protocol version, headers, etc.).
 *
 * <p>Example:
 * <pre>{@code
 * public class MyPlatform extends AdcpPlatform {
 *     @Override
 *     public Object handleTool(String toolName, Object request, AdcpContext ctx) {
 *         return switch (toolName) {
 *             case "get_products" -> getProducts(request, ctx);
 *             default -> super.handleTool(toolName, request, ctx);
 *         };
 *     }
 * }
 * }</pre>
 */
public abstract class AdcpPlatform {

    /**
     * Dispatches a tool call by name. Override this to handle specific tools.
     *
     * <p>The default implementation throws {@link UnsupportedTaskError},
     * signaling that the tool is not implemented.
     *
     * @param toolName the MCP tool name (e.g. "get_products")
     * @param request  the deserialized request object
     * @param ctx      per-request context
     * @return the response object (will be serialized by the framework)
     * @throws UnsupportedTaskError if the tool is not implemented
     */
    public Object handleTool(String toolName, Object request, AdcpContext ctx) {
        throw new UnsupportedTaskError(toolName);
    }

    /**
     * Returns the set of tool names this platform supports.
     *
     * <p>Override this to declare which tools your platform advertises.
     * The default returns an empty set (no tools).
     *
     * @return tool names supported by this platform
     */
    public java.util.Set<String> supportedTools() {
        return java.util.Set.of();
    }

    /**
     * Returns human-readable descriptions for each tool, keyed by tool name.
     *
     * <p>Override this to provide descriptions that help MCP clients
     * (and LLMs) understand when to invoke each tool. If a tool has no
     * entry in this map, its name is used as the description.
     *
     * @return map of tool name → description
     */
    public java.util.Map<String, String> toolDescriptions() {
        return java.util.Map.of();
    }
}
