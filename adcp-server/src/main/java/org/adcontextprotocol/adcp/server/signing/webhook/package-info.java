/**
 * Webhook verification — legacy HMAC-SHA256, Standard Webhooks v1, and
 * RFC 9421 challenge-response proof-of-control.
 *
 * <p>Contains the deprecated HMAC-SHA256 legacy verifier (AdCP 3.x backward
 * compatibility), the Standard Webhooks v1 interop verifier (Svix/Resend),
 * and the webhook challenge proof-of-control utility.
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.server.signing.webhook;