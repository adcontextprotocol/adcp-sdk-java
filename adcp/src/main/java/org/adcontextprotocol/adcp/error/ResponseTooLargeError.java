package org.adcontextprotocol.adcp.error;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * Response body exceeded the configured maximum size.
 *
 * <p>Thrown by the AdCP transports when an agent answers with more bytes than
 * the configured cap allows. The cap is on by default (4 MiB) and is enforced
 * while the body streams in, so an oversized response never reaches the heap
 * in full. See {@code specs/response-size-limits.md}.
 *
 * <p>Raise the cap per client
 * ({@code AdcpClient.builder().maxResponseBytes(...)}), per agent
 * ({@code AgentConfig.builder().maxResponseBytes(...)}) or per call
 * ({@code CallToolOptions.builder().maxResponseBytes(...)}).
 */
public final class ResponseTooLargeError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Depth bound for {@link #findIn} so a self-referential cause chain cannot loop. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private final long limit;
    private final long bytesRead;
    private final @Nullable URI url;

    public ResponseTooLargeError(long limit, long bytesRead, @Nullable URI url) {
        super("RESPONSE_TOO_LARGE",
                "Response exceeded " + limit + " bytes (read " + bytesRead + ")"
                        + (url != null ? " from " + url : ""),
                null);
        this.limit = limit;
        this.bytesRead = bytesRead;
        this.url = url;
    }

    /**
     * Finds a {@code ResponseTooLargeError} in a throwable's cause chain.
     *
     * <p>The cap is enforced inside a {@code java.net.http} body subscriber, and
     * the JDK and the MCP/A2A transports each wrap subscriber failures before
     * they surface. This walks the chain so callers (and the SDK's own transport
     * code, which must not retry an oversized response) can recover the typed
     * error.
     *
     * @param throwable the throwable to inspect, may be {@code null}
     * @return the first {@code ResponseTooLargeError} found, or {@code null}
     */
    public static @Nullable ResponseTooLargeError findIn(@Nullable Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof ResponseTooLargeError error) {
                return error;
            }
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed instanceof ResponseTooLargeError error) {
                    return error;
                }
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }

    /** The configured cap, in bytes. */
    public long limit() {
        return limit;
    }

    /**
     * Bytes counted when the cap was breached — the declared
     * {@code Content-Length} for a pre-rejected response, otherwise the running
     * total at the moment the read was aborted. Not the full body size: the SDK
     * stops reading rather than measure an oversized payload.
     */
    public long bytesRead() {
        return bytesRead;
    }

    /** The URL that produced the oversized response, if known. */
    public @Nullable URI url() {
        return url;
    }
}
