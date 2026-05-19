package org.adcontextprotocol.adcp.server;

import org.adcontextprotocol.adcp.AdcpVersion;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Context passed to {@link AdcpPlatform} tool handlers.
 *
 * <p>Carries per-request metadata: the caller's identity, the negotiated
 * protocol version, and any headers the handler might need.
 *
 * @param adcpVersion the protocol version from the request envelope
 * @param headers     all inbound request headers
 * @param requestId   the MCP request ID (for correlation)
 */
public record AdcpContext(
        @Nullable AdcpVersion adcpVersion,
        Map<String, String> headers,
        @Nullable String requestId
) {

    public AdcpContext {
        headers = Map.copyOf(headers);
    }
}
