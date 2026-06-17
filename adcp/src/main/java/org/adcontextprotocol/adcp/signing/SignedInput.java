package org.adcontextprotocol.adcp.signing;

import java.util.Map;
import java.util.Objects;

/**
 * Raw bytes and parsed headers received from the wire for inbound verification.
 *
 * @param rawBody   the raw request body bytes
 * @param headers   all request headers (verification selects relevant ones)
 * @param method    HTTP method of the inbound request
 * @param targetUri request target URI of the inbound request
 */
public record SignedInput(byte[] rawBody, Map<String, String> headers, String method, String targetUri) {

    public SignedInput {
        Objects.requireNonNull(rawBody, "rawBody");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(targetUri, "targetUri");
    }
}