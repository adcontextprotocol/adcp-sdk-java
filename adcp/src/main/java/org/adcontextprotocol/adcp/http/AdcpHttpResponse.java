package org.adcontextprotocol.adcp.http;

import org.jspecify.annotations.Nullable;

import java.net.http.HttpHeaders;
import java.util.Map;

/**
 * Response from {@link AdcpHttpClient#send}. Wraps the status code,
 * headers, and body — with truncation tracking when the body cap
 * is exceeded.
 *
 * <p><b>Note:</b> Equality comparison is not meaningful for this record
 * because it contains a byte array field. Use explicit content comparison
 * via {@link java.util.Arrays#equals(byte[], byte[])} if needed.
 *
 * @param statusCode   HTTP status code
 * @param headers      response headers
 * @param body         response body (possibly truncated)
 * @param truncated    {@code true} if the body was truncated at the configured cap
 * @param bytesRead    total bytes read before truncation (or full body length)
 */
public record AdcpHttpResponse(
        int statusCode,
        HttpHeaders headers,
        byte[] body,
        boolean truncated,
        long bytesRead
) {

    /**
     * Defensive copy on construction to prevent callers who retain
     * a reference to the input array from mutating response state.
     */
    public AdcpHttpResponse {
        body = body.clone();
    }

    /**
     * Returns a defensive copy of the body bytes.
     * Callers may freely mutate the returned array.
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Returns the body as a UTF-8 string (from the internal copy, no extra clone). */
    public String bodyAsString() {
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Returns the value of a single header, or {@code null} if absent. */
    public @Nullable String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdcpHttpResponse that)) return false;
        return statusCode == that.statusCode
                && truncated == that.truncated
                && bytesRead == that.bytesRead
                && java.util.Arrays.equals(body, that.body)
                && headers.equals(that.headers);
    }

    @Override
    public int hashCode() {
        int h = Integer.hashCode(statusCode);
        h = 31 * h + headers.hashCode();
        h = 31 * h + java.util.Arrays.hashCode(body);
        h = 31 * h + Boolean.hashCode(truncated);
        h = 31 * h + Long.hashCode(bytesRead);
        return h;
    }
}
