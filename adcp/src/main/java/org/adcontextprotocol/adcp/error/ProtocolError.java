package org.adcontextprotocol.adcp.error;

import org.jspecify.annotations.Nullable;

/** Wraps MCP or A2A transport failures. */
public final class ProtocolError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String protocol;

    public ProtocolError(String protocol, String message, @Nullable Throwable cause) {
        super("PROTOCOL_ERROR", message, null, cause);
        this.protocol = protocol;
    }

    /** {@code "mcp"} or {@code "a2a"}. */
    public String protocol() {
        return protocol;
    }
}
