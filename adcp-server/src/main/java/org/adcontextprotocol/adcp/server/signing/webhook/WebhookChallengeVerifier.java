package org.adcontextprotocol.adcp.server.signing.webhook;

import org.jspecify.annotations.Nullable;

/**
 * Verifier for webhook challenge proof-of-control per the AdCP spec.
 *
 * <p>When a buyer registers a webhook URL, the seller sends a POST with a
 * challenge payload. The receiver MUST verify:
 * <ol>
 *   <li>The sender identity (seller verification via RFC 9421 signature)</li>
 *   <li>The delivery auth metadata matches expectations</li>
 *   <li>The event type is {@code "webhook_activation"}</li>
 *   <li>The challenge in the response matches the sent challenge</li>
 *   <li>The timestamp is within the acceptable window</li>
 * </ol>
 *
 * <p>This verifier handles steps 3–5. Steps 1–2 are the caller's responsibility
 * (they should use the RFC 9421 verifier for those).
 */
public final class WebhookChallengeVerifier {

    private static final String ACTIVATION_EVENT_TYPE = "webhook_activation";
    private static final long DEFAULT_CHALLENGE_WINDOW_SECONDS = 300;

    private WebhookChallengeVerifier() {}

    /**
     * Verify a challenge response against the original challenge.
     *
     * @param original     the challenge that was sent
     * @param response     the challenge received back
     * @param referenceNow the current Unix epoch seconds for window validation
     * @return a verification result
     */
    public static ChallengeVerificationResult verify(
            WebhookChallenge original,
            WebhookChallenge response,
            long referenceNow) {
        return verify(original, response, referenceNow, DEFAULT_CHALLENGE_WINDOW_SECONDS);
    }

    /**
     * Verify a challenge response with a custom window.
     *
     * @param original            the challenge that was sent
     * @param response            the challenge received back
     * @param referenceNow        the current Unix epoch seconds
     * @param challengeWindowSeconds the accepted skew window
     * @return a verification result
     */
    public static ChallengeVerificationResult verify(
            WebhookChallenge original,
            WebhookChallenge response,
            long referenceNow,
            long challengeWindowSeconds) {

        if (!ACTIVATION_EVENT_TYPE.equals(response.eventType())) {
            return new ChallengeVerificationResult.Invalid(
                    "invalid_event_type",
                    "Expected event type '" + ACTIVATION_EVENT_TYPE + "', got '"
                            + response.eventType() + "'");
        }

        if (!original.challenge().equals(response.challenge())) {
            return new ChallengeVerificationResult.Invalid(
                    "challenge_mismatch",
                    "Challenge mismatch: expected '" + original.challenge()
                            + "', got '" + response.challenge() + "'");
        }

        long skew = Math.abs(referenceNow - response.timestamp());
        if (skew > challengeWindowSeconds) {
            return new ChallengeVerificationResult.Invalid(
                    "stale_timestamp",
                    "Timestamp skew " + skew + "s exceeds window " + challengeWindowSeconds + "s");
        }

        return new ChallengeVerificationResult.Valid();
    }
}