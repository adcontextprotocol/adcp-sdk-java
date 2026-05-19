package org.adcontextprotocol.adcp.transport.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.adcontextprotocol.adcp.error.ProtocolError;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Calls MCP tools via an established {@link McpSyncClient} connection.
 *
 * <p>Wraps the MCP SDK's {@code callTool()} API, extracting structured
 * content and deserializing to the target response type.
 */
public final class McpCaller {

    private static final Logger log = LoggerFactory.getLogger(McpCaller.class);

    /** Maximum allowed TextContent length (10 MB, matching ObjectMapper limits). */
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    public McpCaller(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Calls an MCP tool and deserializes the response.
     *
     * @param client       the connected MCP client
     * @param toolName     the MCP tool name (e.g. "get_products")
     * @param args         the merged arguments (including version envelope)
     * @param responseType the expected response type
     * @param <T>          response type
     * @return the deserialized response
     * @throws ProtocolError if the call fails or the response is unparseable
     */
    public <T> T callTool(McpSyncClient client, String toolName,
                          Map<String, Object> args, Class<T> responseType) {
        try {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
            McpSchema.CallToolResult result = client.callTool(request);

            return extractResponse(result, responseType);
        } catch (ProtocolError e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolError("mcp",
                    "MCP callTool failed for " + toolName + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T extractResponse(McpSchema.CallToolResult result, Class<T> responseType) {
        // If the tool itself reported an error, surface it before trying
        // to deserialize the content as a success payload.
        if (Boolean.TRUE.equals(result.isError())) {
            String errorText = extractErrorText(result);
            throw new ProtocolError("mcp", "MCP tool returned an error: " + errorText, null);
        }

        if (result.content() == null || result.content().isEmpty()) {
            throw new ProtocolError("mcp", "Empty response from MCP callTool", null);
        }

        // Try to find structured (JSON) content
        Exception firstParseError = null;
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (textContent.text() != null
                        && textContent.text().length() > MAX_CONTENT_LENGTH) {
                    throw new ProtocolError("mcp",
                            "MCP response content exceeds size limit ("
                                    + textContent.text().length() + " > "
                                    + MAX_CONTENT_LENGTH + ")", null);
                }
                try {
                    return objectMapper.readValue(textContent.text(), responseType);
                } catch (Exception e) {
                    if (firstParseError == null) firstParseError = e;
                    log.debug("Failed to parse TextContent as {}: {}",
                            responseType.getSimpleName(), e.getMessage());
                }
            }
        }

        // If no parseable content found, try converting the first content item
        McpSchema.Content first = result.content().getFirst();
        try {
            JsonNode node = objectMapper.valueToTree(first);
            return objectMapper.treeToValue(node, responseType);
        } catch (Exception e) {
            if (firstParseError != null) e.addSuppressed(firstParseError);
            throw new ProtocolError("mcp",
                    "Cannot deserialize MCP response to " + responseType.getSimpleName(),
                    e);
        }
    }

    private static final int MAX_ERROR_LENGTH = 500;

    private String extractErrorText(McpSchema.CallToolResult result) {
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent tc) {
                    return sanitizeErrorText(tc.text());
                }
            }
        }
        return "(no error detail)";
    }

    private static String sanitizeErrorText(String raw) {
        if (raw == null) {
            return "(no error detail)";
        }
        String truncated = raw.length() > MAX_ERROR_LENGTH
                ? raw.substring(0, MAX_ERROR_LENGTH) + "..."
                : raw;
        // Strip control characters (except tab/newline) to prevent
        // injection into downstream systems (logs, LLM context)
        return truncated.replaceAll("[\\p{Cc}&&[^\t\n]]", "");
    }
}
