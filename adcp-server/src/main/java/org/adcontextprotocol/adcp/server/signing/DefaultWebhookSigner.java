package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SigningContext;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.adcontextprotocol.adcp.signing.SigningInput;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Default implementation of {@link WebhookSigner} that uses
 * {@link InProcessSigningProvider} for signing.
 *
 * <p>Applies the AdCP webhook-signing profile:
 * <ul>
 *   <li>tag = "adcp/webhook-signing/v1"</li>
 *   <li>Content-Digest is required for all webhook payloads</li>
 *   <li>300-second replay window</li>
 *   <li>Covers: @method, @target-uri, @authority, content-type, content-digest</li>
 * </ul>
 */
public final class DefaultWebhookSigner implements WebhookSigner {

    private final InProcessSigningProvider signingProvider;

    /**
     * Create a DefaultWebhookSigner with the given signing provider.
     *
     * @param signingProvider the in-process signing provider
     */
    public DefaultWebhookSigner(InProcessSigningProvider signingProvider) {
        this.signingProvider = Objects.requireNonNull(signingProvider, "signingProvider");
    }

    @Override
    public WebhookSigningResult sign(SigningContext context, String method, String targetUri, byte[] body, Map<String, String> existingHeaders) {
        if (context.use() != AdcpUse.WEBHOOK_SIGNING) {
            throw new IllegalArgumentException("WebhookSigner requires SigningContext with WEBHOOK_SIGNING use, got: " + context.use());
        }

        Map<String, String> headers = new LinkedHashMap<>(existingHeaders);
        // Content-Digest is required for webhook signing, even for empty bodies.
        // The digest of an empty byte array is a valid Content-Digest value.
        if (body != null) {
            headers.putIfAbsent("content-digest", ContentDigest.sha256(body));
        }

        SigningInput input = new SimpleSigningInput(method, targetUri, body, headers);

        try {
            org.adcontextprotocol.adcp.signing.Signature sig = signingProvider.sign(context, input);

            String contentDigestValue = headers.get("content-digest");

            return new WebhookSigningResult(
                    sig.signatureInput(),
                    sig.label() + "=:" + ContentDigest.base64UrlNoPadding(sig.signatureBytes()) + ":",
                    contentDigestValue);
        } catch (SigningException e) {
            throw new RuntimeException("Failed to sign webhook payload", e);
        }
    }

    private record SimpleSigningInput(
            String method,
            String targetUri,
            byte[] body,
            Map<String, String> headers
    ) implements SigningInput {}
}