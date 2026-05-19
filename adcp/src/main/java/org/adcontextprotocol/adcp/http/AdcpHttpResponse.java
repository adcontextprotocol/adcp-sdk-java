package org.adcontextprotocol.adcp.http;

import org.jspecify.annotations.Nullable;

import java.net.http.HttpHeaders;
import java.util.Map;

/**
 * Response from {@link AdcpHttpClient#send}. Wraps the status code,
 * headers, and body — with truncation tracking when the body cap
 * is exceeded.
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

    /** Defensive copy to prevent callers from mutating the response body. */
    public AdcpHttpResponse {
        body = body.clone();
    }

    /** Returns the body as a UTF-8 string. */
    public String bodyAsString() {
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Returns the value of a single header, or {@code null} if absent. */
    public @Nullable String header(String name) {
        return headers.firstValue(name).orElse(null);
    }
}
