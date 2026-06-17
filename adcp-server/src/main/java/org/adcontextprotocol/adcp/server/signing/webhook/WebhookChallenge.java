package org.adcontextprotocol.adcp.server.signing.webhook;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * Webhook challenge data for proof-of-control verification per the AdCP spec.
 *
 * <p>When a buyer registers a webhook URL, the seller sends a POST to the
 * webhook URL with a challenge payload. The receiver MUST verify the seller
 * identity, delivery auth metadata, and event type before echoing. This
 * record holds the challenge data.
 *
 * @param challenge  the opaque challenge string to echo back
 * @param eventType  the event type (e.g. {@code "webhook_activation"})
 * @param timestamp  Unix epoch seconds when the challenge was created
 */
public record WebhookChallenge(String challenge, String eventType, long timestamp) {

    public WebhookChallenge {
        Objects.requireNonNull(challenge, "challenge");
        Objects.requireNonNull(eventType, "eventType");
        if (challenge.isEmpty()) {
            throw new IllegalArgumentException("challenge must not be empty");
        }
        if (eventType.isEmpty()) {
            throw new IllegalArgumentException("eventType must not be empty");
        }
        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp must be positive");
        }
    }

    /**
     * Create a challenge with the current timestamp.
     */
    public static WebhookChallenge of(String challenge, String eventType) {
        return new WebhookChallenge(challenge, eventType, Instant.now().getEpochSecond());
    }
}