package org.adcontextprotocol.adcp.transport.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.adcontextprotocol.adcp.error.ProtocolError;
import org.adcontextprotocol.adcp.error.ResponseTooLargeError;
import org.adcontextprotocol.adcp.http.AdcpHttpClient;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Calls MCP tools via an established {@link McpSyncClient} connection.
 *
 * <p>Wraps the MCP SDK's {@code callTool()} API, extracting structured
 * content and deserializing to the target response type.
 *
 * <p>The authoritative response size cap lives in the transport's HTTP client
 * (see {@link org.adcontextprotocol.adcp.http.ResponseSizeLimitingHttpClient}),
 * which aborts an oversized body while it streams. The content checks here are
 * a backstop for payloads that arrive by some other route, and they translate a
 * transport-level {@link ResponseTooLargeError} back into the typed error
 * instead of burying it in a {@link ProtocolError}.
 */
public final class McpCaller {

    private static final Logger log = LoggerFactory.getLogger(McpCaller.class);

    private final ObjectMapper objectMapper;

    public McpCaller(ObjectMapper objectMapper) {
        // Harden the ObjectMapper against polymorphic deserialization attacks.
        // When responseType is Object.class or Map.class, a default-typed
        // mapper could instantiate arbitrary classes from incoming JSON
        // (gadget-chain attacks). We defensively disable these features.
        this.objectMapper = objectMapper.copy();
        this.objectMapper.deactivateDefaultTyping();
    }

    /**
     * Calls an MCP tool and deserializes the response.
     *
     * @param client       the connected MCP client
     * @param toolName     the MCP tool name (e.g. "get_products")
     * @param args         the merged arguments (including version envelope)
     * <p>Uses the SDK's default 4 MiB cap as its content backstop, not the cap
     * configured on the calling client — the transport's own cap is the
     * authoritative one. Prefer
     * {@link #callTool(McpSyncClient, String, Map, Class, long)}.
     *
     * @param responseType the expected response type
     * @param <T>          response type
     * @return the deserialized response
     * @throws ProtocolError if the call fails or the response is unparseable
     */
    public <T> T callTool(McpSyncClient client, String toolName,
                          Map<String, Object> args, Class<T> responseType) {
        return callTool(client, toolName, args, responseType,
                AdcpHttpClient.DEFAULT_MAX_CALL_RESPONSE_BYTES);
    }

    /**
     * Calls an MCP tool and deserializes the response, rejecting content larger
     * than {@code maxResponseBytes}.
     *
     * @param maxResponseBytes the effective response size cap for this call
     * @throws ResponseTooLargeError if the agent's response exceeds the cap
     */
    public <T> T callTool(McpSyncClient client, String toolName,
                          Map<String, Object> args, Class<T> responseType,
                          long maxResponseBytes) {
        try {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
            McpSchema.CallToolResult result = client.callTool(request);

            return extractResponse(result, responseType, maxResponseBytes);
        } catch (ProtocolError e) {
            throw e;
        } catch (Exception e) {
            // The cap is enforced inside the JDK body subscriber, so the typed
            // error arrives wrapped. Surface it rather than hiding it in a
            // ProtocolError, which the retry path would treat as transient.
            ResponseTooLargeError tooLarge = ResponseTooLargeError.findIn(e);
            if (tooLarge != null) {
                throw tooLarge;
            }
            // The MCP SDK's exception messages carry server-supplied text;
            // strip control characters before it reaches a log or an LLM.
            throw new ProtocolError("mcp",
                    "MCP callTool failed for " + toolName + ": "
                            + sanitizeErrorText(e.getMessage()), e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T extractResponse(McpSchema.CallToolResult result, Class<T> responseType,
                                  long maxResponseBytes) {
        // If the tool itself reported an error, surface it before trying
        // to deserialize the content as a success payload.
        if (Boolean.TRUE.equals(result.isError())) {
            String errorText = extractErrorText(result);
            throw new ProtocolError("mcp", "MCP tool returned an error: " + errorText, null);
        }

        // MCP 2025-06-18: prefer structuredContent over content[] for typed payloads.
        Object structured = result.structuredContent();
        if (structured != null) {
            try {
                JsonNode node = objectMapper.valueToTree(structured);
                return objectMapper.treeToValue(node, responseType);
            } catch (Exception e) {
                log.debug("Failed to parse structuredContent as {}: {}",
                        responseType.getSimpleName(), e.getMessage());
                // Fall through to content[] path
            }
        }

        if (result.content() == null || result.content().isEmpty()) {
            if (structured != null) {
                throw new ProtocolError("mcp",
                        "Cannot deserialize structuredContent to "
                                + responseType.getSimpleName(), null);
            }
            throw new ProtocolError("mcp", "Empty response from MCP callTool", null);
        }

        // Fall back to content[] TextContent path
        Exception firstParseError = null;
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                String text = textContent.text();
                if (text == null) {
                    log.debug("Skipping TextContent with null text");
                    continue;
                }
                // UTF-8 is never fewer bytes than chars, so a char count over
                // the cap is already over the cap — reject without measuring.
                if (text.length() > maxResponseBytes) {
                    throw new ResponseTooLargeError(maxResponseBytes, text.length(), null);
                }
                try {
                    return objectMapper.readValue(text, responseType);
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
