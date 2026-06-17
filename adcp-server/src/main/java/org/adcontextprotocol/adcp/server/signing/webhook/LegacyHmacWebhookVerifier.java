package org.adcontextprotocol.adcp.server.signing.webhook;

import org.adcontextprotocol.adcp.server.signing.replay.ReplayStore;
import org.jspecify.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Legacy HMAC-SHA256 webhook verifier per the AdCP 3.x specification.
 *
 * <p><b>Deprecated.</b> This verifier implements the legacy HMAC-SHA256
 * webhook authentication scheme that is removed in AdCP 4.0. New integrations
 * should use the RFC 9421 webhook signing profile ({@code Rfc9421Verifier}).
 * This class exists solely for backward compatibility with buyers who haven't
 * adopted RFC 9421 yet.
 *
 * <p>Wire format:
 * <ul>
 *   <li>{@code X-AdCP-Signature}: {@code sha256=<hex_digest>} of
 *       {@code HMAC_SHA256(secret, timestamp + "." + raw_body_bytes)}</li>
 *   <li>{@code X-AdCP-Timestamp}: Unix epoch seconds</li>
 * </ul>
 *
 * <p>Verification steps:
 * <ol>
 *   <li>Check both headers are present</li>
 *   <li>Check timestamp is within the replay window (default 300 seconds)</li>
 *   <li>Compute HMAC with current (and previous, if provided) secrets</li>
 *   <li>Constant-time compare</li>
 *   <li>Check nonce for replay (delegated to {@link ReplayStore})</li>
 * </ol>
 *
 * @deprecated Use RFC 9421 webhook signing instead. This verifier is removed
 * in AdCP 4.0.
 */
@Deprecated(since = "3.1", forRemoval = true)
public final class LegacyHmacWebhookVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-AdCP-Signature";
    private static final String TIMESTAMP_HEADER = "X-AdCP-Timestamp";
    private static final String HEX_PREFIX = "sha256=";
    private static final long DEFAULT_WINDOW_SECONDS = 300;
    private static final int MIN_SECRET_LENGTH = 32;

    private final ReplayStore replayStore;
    private final long windowSeconds;

    /**
     * Create a verifier with the default 300-second window.
     *
     * @param replayStore the store for replay detection
     */
    public LegacyHmacWebhookVerifier(ReplayStore replayStore) {
        this(replayStore, DEFAULT_WINDOW_SECONDS);
    }

    /**
     * Create a verifier with a custom window.
     *
     * @param replayStore    the store for replay detection
     * @param windowSeconds  the accepted timestamp skew window in seconds
     */
    public LegacyHmacWebhookVerifier(ReplayStore replayStore, long windowSeconds) {
        if (replayStore == null) throw new NullPointerException("replayStore");
        if (windowSeconds <= 0) throw new IllegalArgumentException("windowSeconds must be positive");
        this.replayStore = replayStore;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Verify an HMAC-SHA256 signed webhook body.
     *
     * @param headers         the request headers (case-insensitive lookup)
     * @param body            the raw request body bytes
     * @param currentSecret   the current shared secret (minimum 32 bytes)
     * @param previousSecret  the previous secret for rotation, or null
     * @param referenceNow     the current Unix epoch seconds for window validation
     * @return the verification result
     */
    public HmacVerificationResult verify(
            Map<String, String> headers,
            byte[] body,
            byte[] currentSecret,
            @Nullable byte[] previousSecret,
            long referenceNow) {

        if (currentSecret == null || currentSecret.length < MIN_SECRET_LENGTH) {
            return new HmacVerificationResult.SecretNotFound();
        }

        String sigValue = getHeader(headers, SIGNATURE_HEADER);
        String tsValue = getHeader(headers, TIMESTAMP_HEADER);

        if (sigValue == null || tsValue == null) {
            return new HmacVerificationResult.Invalid("missing_header",
                    "Missing X-AdCP-Signature or X-AdCP-Timestamp header");
        }

        if (!sigValue.startsWith(HEX_PREFIX)) {
            return new HmacVerificationResult.Invalid("invalid_format",
                    "Signature must start with '" + HEX_PREFIX + "'");
        }
        String hexSig = sigValue.substring(HEX_PREFIX.length());

        long timestamp;
        try {
            timestamp = Long.parseLong(tsValue);
        } catch (NumberFormatException e) {
            return new HmacVerificationResult.Invalid("invalid_timestamp",
                    "Invalid timestamp: " + tsValue);
        }

        long skew = Math.abs(referenceNow - timestamp);
        if (skew > windowSeconds) {
            return new HmacVerificationResult.StaleTimestamp(skew);
        }

        byte[] message = (tsValue + ".").getBytes(StandardCharsets.UTF_8);
        byte[] fullMessage = concat(message, body);

        String expected = hmacSha256Hex(currentSecret, fullMessage);
        if (constantTimeEquals(expected, hexSig)) {
            if (replayStore.checkAndStore("hmac", tsValue + ":" + hexSig)) {
                return new HmacVerificationResult.ReplayDetected();
            }
            return new HmacVerificationResult.Valid();
        }

        if (previousSecret != null && previousSecret.length >= MIN_SECRET_LENGTH) {
            String prevExpected = hmacSha256Hex(previousSecret, fullMessage);
            if (constantTimeEquals(prevExpected, hexSig)) {
                if (replayStore.checkAndStore("hmac", tsValue + ":" + hexSig)) {
                    return new HmacVerificationResult.ReplayDetected();
                }
                return new HmacVerificationResult.Valid();
            }
        }

        return new HmacVerificationResult.Invalid("signature_mismatch",
                "Signature did not match");
    }

    private static @Nullable String getHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String hmacSha256Hex(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            byte[] hash = mac.doFinal(message);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
}