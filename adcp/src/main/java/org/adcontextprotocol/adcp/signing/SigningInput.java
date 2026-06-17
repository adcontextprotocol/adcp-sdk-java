package org.adcontextprotocol.adcp.signing;

import java.util.Map;

/**
 * Structured input for outbound signing. The canonicalizer uses these fields
 * to build the RFC 9421 §2.5 signature base.
 */
public interface SigningInput {

    /** HTTP method (e.g. {@code "POST"}, {@code "GET"}). */
    String method();

    /** Request target URI (e.g. {@code "/v3/webhooks"}). */
    String targetUri();

    /** Raw request body bytes. */
    byte[] body();

    /**
     * Request headers relevant to the signature. The canonicalizer picks
     * which headers to include in the signature base based on the
     * {@code adcp_use} and the AdCP signing profile.
     */
    Map<String, String> headers();
}