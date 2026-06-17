package org.adcontextprotocol.adcp.server.signing.webhook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebhookChallengeVerifierTest {

    @Test
    void verify_matchingChallenge_returnsValid() {
        WebhookChallenge original = new WebhookChallenge("abc123", "webhook_activation", 1000000);
        WebhookChallenge response = new WebhookChallenge("abc123", "webhook_activation", 1000000);

        ChallengeVerificationResult result = WebhookChallengeVerifier.verify(original, response, 1000000);
        assertInstanceOf(ChallengeVerificationResult.Valid.class, result);
    }

    @Test
    void verify_mismatchedChallenge_returnsInvalid() {
        WebhookChallenge original = new WebhookChallenge("abc123", "webhook_activation", 1000000);
        WebhookChallenge response = new WebhookChallenge("wrong", "webhook_activation", 1000000);

        ChallengeVerificationResult result = WebhookChallengeVerifier.verify(original, response, 1000000);
        assertInstanceOf(ChallengeVerificationResult.Invalid.class, result);
        assertEquals("challenge_mismatch", ((ChallengeVerificationResult.Invalid) result).errorCode());
    }

    @Test
    void verify_wrongEventType_returnsInvalid() {
        WebhookChallenge original = new WebhookChallenge("abc123", "webhook_activation", 1000000);
        WebhookChallenge response = new WebhookChallenge("abc123", "delivery", 1000000);

        ChallengeVerificationResult result = WebhookChallengeVerifier.verify(original, response, 1000000);
        assertInstanceOf(ChallengeVerificationResult.Invalid.class, result);
        assertEquals("invalid_event_type", ((ChallengeVerificationResult.Invalid) result).errorCode());
    }

    @Test
    void verify_staleTimestamp_returnsInvalid() {
        WebhookChallenge original = new WebhookChallenge("abc123", "webhook_activation", 1000000);
        WebhookChallenge response = new WebhookChallenge("abc123", "webhook_activation", 1000000);

        ChallengeVerificationResult result = WebhookChallengeVerifier.verify(original, response, 1000600);
        assertInstanceOf(ChallengeVerificationResult.Invalid.class, result);
        assertEquals("stale_timestamp", ((ChallengeVerificationResult.Invalid) result).errorCode());
    }

    @Test
    void verify_withinWindow_returnsValid() {
        WebhookChallenge original = new WebhookChallenge("abc123", "webhook_activation", 1000000);
        WebhookChallenge response = new WebhookChallenge("abc123", "webhook_activation", 1000200);

        ChallengeVerificationResult result = WebhookChallengeVerifier.verify(original, response, 1000200);
        assertInstanceOf(ChallengeVerificationResult.Valid.class, result);
    }

    @Test
    void webhookChallenge_recordValidation() {
        assertThrows(NullPointerException.class,
                () -> new WebhookChallenge(null, "webhook_activation", 1000));
        assertThrows(NullPointerException.class,
                () -> new WebhookChallenge("abc", null, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new WebhookChallenge("", "webhook_activation", 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new WebhookChallenge("abc", "", 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new WebhookChallenge("abc", "webhook_activation", 0));
    }

    @Test
    void webhookChallenge_of_createsWithTimestamp() {
        WebhookChallenge challenge = WebhookChallenge.of("test-challenge", "webhook_activation");
        assertEquals("test-challenge", challenge.challenge());
        assertEquals("webhook_activation", challenge.eventType());
        assertTrue(challenge.timestamp() > 0);
    }
}