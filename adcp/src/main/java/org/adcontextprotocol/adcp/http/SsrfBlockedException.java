package org.adcontextprotocol.adcp.http;

/**
 * Thrown when an outbound request is blocked by the {@link SsrfPolicy}.
 *
 * <p>The {@link #reason()} describes the blocked range (e.g. "loopback",
 * "RFC 1918 private") without echoing the actual address, to avoid
 * leaking host structure to callers.
 */
public final class SsrfBlockedException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String host;
    private final String reason;

    SsrfBlockedException(String host, String reason) {
        super("SSRF blocked: " + reason);
        this.host = host;
        this.reason = reason;
    }

    /** The hostname or IP that was blocked. */
    public String host() {
        return host;
    }

    /** Why the address was blocked (range description, not the address). */
    public String reason() {
        return reason;
    }
}
