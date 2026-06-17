package org.adcontextprotocol.adcp.server.signing;

import org.jspecify.annotations.Nullable;

/**
 * Result of signing a webhook payload. Contains the headers to add to the outbound request.
 */
public record WebhookSigningResult(
    String signatureInput,
    String signature,
    @Nullable String contentDigest
) {}