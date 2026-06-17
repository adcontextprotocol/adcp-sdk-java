package org.adcontextprotocol.adcp.server.signing.jws;

/**
 * Parsed JWS document with header, payload, and signature bytes.
 *
 * @param b64ProtectedHeader the base64url-encoded protected header (original wire form)
 * @param b64Payload         the base64url-encoded payload (original wire form)
 * @param signature          the decoded signature bytes
 */
public record JwsDocument(String b64ProtectedHeader, String b64Payload, byte[] signature) {

    public JwsDocument {
        if (b64ProtectedHeader == null) throw new NullPointerException("b64ProtectedHeader");
        if (b64Payload == null) throw new NullPointerException("b64Payload");
        if (signature == null) throw new NullPointerException("signature");
    }
}