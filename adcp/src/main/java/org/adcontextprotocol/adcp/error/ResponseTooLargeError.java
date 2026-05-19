package org.adcontextprotocol.adcp.error;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/** Response body exceeded the configured maximum size. */
public final class ResponseTooLargeError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

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

    public long limit() {
        return limit;
    }

    public long bytesRead() {
        return bytesRead;
    }

    public @Nullable URI url() {
        return url;
    }
}
