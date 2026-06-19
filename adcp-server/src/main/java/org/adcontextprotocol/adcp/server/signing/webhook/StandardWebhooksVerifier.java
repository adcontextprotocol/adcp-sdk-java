package org.adcontextprotocol.adcp.server.signing.webhook;

import org.jspecify.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

/**
 * Standard Webhooks v1 verification (Svix/Resend interop).
 *
 * <p>Implements verification for the Standard Webhooks v1 format as defined by
 * <a href="https://www.standardwebhooks.com/">standardwebhooks.com</a>. This
 * is separate from the AdCP RFC 9421 profile but needed for webhook
 * interoperability with services like Svix and Resend.
 *
 * <p>Wire format:
 * <ul>
 *   <li>{@code webhook-id} — unique identifier for this delivery</li>
 *   <li>{@code webhook-timestamp} — Unix epoch seconds, string</li>
 *   <li>{@code webhook-signature} — one or more space-separated tokens of the form
 *       {@code v1,<base64>} where the base64 value is
 *       {@code HMAC-SHA256(secret, msg_id + "." + timestamp + "." + body)}</li>
 * </ul>
 *
 * <p>Secrets are typically distributed in the canonical {@code whsec_<base64>}
 * form. Use {@link #decodeSecret(String)} to obtain the raw bytes.
 *
 * <p><b>Not AdCP normative.</b> This verifier exists for interop with external
 * webhook providers. AdCP-native webhooks use RFC 9421 signing.
 */
public final class StandardWebhooksVerifier {

    private StandardWebhooksVerifier() {}

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String HEADER_ID = "webhook-id";
    private static final String HEADER_TIMESTAMP = "webhook-timestamp";
    private static final String HEADER_SIGNATURE = "webhook-signature";
    private static final String SECRET_PREFIX = "whsec_";
    private static final String SIGNATURE_VERSION = "v1";
    private static final int DEFAULT_TOLERANCE_SECONDS = 300;

    /**
     * Decode a {@code whsec_<base64>} secret to raw HMAC key bytes.
     *
     * <p>Accepts both with-prefix ({@code whsec_AAAA...}) and without.
     * Padding is permissive.
     *
     * @param secret the secret string, optionally prefixed with {@code whsec_}
     * @return the decoded raw bytes
     * @throws IllegalArgumentException if the secret is empty or not valid base64
     */
    public static byte[] decodeSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("secret must be a non-empty string");
        }
        String payload = secret.startsWith(SECRET_PREFIX)
                ? secret.substring(SECRET_PREFIX.length())
                : secret;
        int padLen = (4 - payload.length() % 4) % 4;
        String padded = payload + "=".repeat(padLen);
        try {
            return Base64.getMimeDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("secret is not valid base64", e);
        }
    }

    /**
     * Produce the three Standard Webhooks v1 headers for an outgoing POST.
     *
     * @param secret    the raw secret bytes (use {@link #decodeSecret} if you have
     *                  a {@code whsec_} prefixed string)
     * @param msgId     the unique message identifier
     * @param timestamp Unix epoch seconds
     * @param body      the raw request body bytes
     * @return a map of header names to values
     */
    public static Map<String, String> sign(byte[] secret, String msgId, int timestamp, byte[] body) {
        if (msgId == null || msgId.isEmpty()) {
            throw new IllegalArgumentException("msgId must be non-empty");
        }
        String message = msgId + "." + timestamp + ".";
        byte[] fullMessage = concat(message.getBytes(StandardCharsets.UTF_8), body);
        byte[] digest = hmacSha256(secret, fullMessage);
        String encoded = Base64.getEncoder().encodeToString(digest);
        return Map.of(
                HEADER_ID, msgId,
                HEADER_TIMESTAMP, String.valueOf(timestamp),
                HEADER_SIGNATURE, SIGNATURE_VERSION + "," + encoded
        );
    }

    /**
     * Verify a Standard Webhooks v1 signed POST.
     *
     * <p>Returns {@code true} if verification succeeds. Multiple v1 signatures
     * in {@code webhook-signature} are accepted (for key rotation) — verification
     * succeeds if any one of them matches.
     *
     * @param headers           the request headers (case-insensitive lookup)
     * @param body              the raw request body bytes
     * @param secret            the raw secret bytes
     * @param now               the current Unix epoch seconds
     * @param toleranceSeconds the accepted timestamp skew window (default 300)
     * @return {@code true} if verification succeeds
     * @throws StandardWebhookVerificationException if verification fails
     */
    public static boolean verify(Map<String, String> headers, byte[] body, byte[] secret,
            long now, int toleranceSeconds) {
        String msgId = getHeader(headers, HEADER_ID);
        String tsValue = getHeader(headers, HEADER_TIMESTAMP);
        String sigHeader = getHeader(headers, HEADER_SIGNATURE);

        if (msgId == null || tsValue == null || sigHeader == null) {
            throw new StandardWebhookVerificationException(
                    "missing webhook-id, webhook-timestamp, or webhook-signature header");
        }

        long ts;
        try {
            ts = Long.parseLong(tsValue);
        } catch (NumberFormatException e) {
            throw new StandardWebhookVerificationException("invalid webhook-timestamp: " + tsValue);
        }

        long skew = Math.abs(now - ts);
        if (skew > toleranceSeconds) {
            throw new StandardWebhookVerificationException(
                    "timestamp skew " + skew + "s exceeds tolerance " + toleranceSeconds + "s");
        }

        String message = msgId + "." + tsValue + ".";
        byte[] fullMessage = concat(message.getBytes(StandardCharsets.UTF_8), body);
        String expected = Base64.getEncoder().encodeToString(hmacSha256(secret, fullMessage));

        for (String token : sigHeader.split(" ")) {
            int commaIdx = token.indexOf(',');
            if (commaIdx < 0) continue;
            String version = token.substring(0, commaIdx);
            String value = token.substring(commaIdx + 1);
            if (!SIGNATURE_VERSION.equals(version) || value.isEmpty()) continue;
            if (constantTimeEquals(expected, value)) {
                return true;
            }
        }

        throw new StandardWebhookVerificationException("no matching v1 signature");
    }

    /**
     * Verify with default 300-second tolerance.
     */
    public static boolean verify(Map<String, String> headers, byte[] body, byte[] secret, long now) {
        return verify(headers, body, secret, now, DEFAULT_TOLERANCE_SECONDS);
    }

    private static @Nullable String getHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static byte[] hmacSha256(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * Exception thrown when Standard Webhooks verification fails.
     */
    public static final class StandardWebhookVerificationException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public StandardWebhookVerificationException(String message) {
            super(message);
        }
    }
}