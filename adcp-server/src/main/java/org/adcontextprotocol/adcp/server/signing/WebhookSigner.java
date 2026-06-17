package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.SigningContext;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Signs outbound webhook payloads using RFC 9421.
 *
 * <p>Track 6 (async-l3) will call this interface when sending webhooks.
 * The implementation uses {@link Rfc9421Signer} internally and applies
 * the AdCP webhook-signing profile (tag="adcp/webhook-signing/v1",
 * content-digest required, 300s replay window).
 */
public interface WebhookSigner {

    /**
     * Signs a webhook payload and returns the signed headers to add to the outbound request.
     *
     * @param context         signing context with purpose, tenant, and principal
     * @param method          HTTP method (always POST for webhooks)
     * @param targetUri       the webhook endpoint URI
     * @param body            raw body bytes (MUST NOT be re-serialized — sign the bytes on the wire)
     * @param existingHeaders headers already present on the request (e.g., Content-Type)
     * @return signed headers to add to the outbound request
     */
    WebhookSigningResult sign(SigningContext context, String method, String targetUri, byte[] body, Map<String, String> existingHeaders);
}