package org.adcontextprotocol.adcp.server.signing.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StandardWebhooksVerifierTest {

    private static final byte[] SECRET = StandardWebhooksVerifier.decodeSecret("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo1laRYLJ2sFB5pO8Jyk=");

    @Test
    void decodeSecret_withPrefix_decodesSuccessfully() {
        byte[] decoded = StandardWebhooksVerifier.decodeSecret("whsec_QTJjMmE1ZTgtNjg3NS00MjI3LThkZWUtOWI4Y2I1ZWE5MzA5");
        assertNotNull(decoded);
        assertTrue(decoded.length > 0);
    }

    @Test
    void decodeSecret_withoutPrefix_decodesSuccessfully() {
        byte[] decoded = StandardWebhooksVerifier.decodeSecret("QTJjMmE1ZTgtNjg3NS00MjI3LThkZWUtOWI4Y2I1ZWE5MzA5");
        assertNotNull(decoded);
        assertTrue(decoded.length > 0);
    }

    @Test
    void decodeSecret_empty_throws() {
        assertThrows(IllegalArgumentException.class, () -> StandardWebhooksVerifier.decodeSecret(""));
    }

    @Test
    void signAndVerify_roundTrip() {
        long timestamp = System.currentTimeMillis() / 1000;
        byte[] body = "{\"event\":\"test\"}".getBytes(StandardCharsets.UTF_8);

        Map<String, String> signed = StandardWebhooksVerifier.sign(SECRET, "msg_123", (int) timestamp, body);

        assertTrue(StandardWebhooksVerifier.verify(signed, body, SECRET, timestamp));
    }

    @Test
    void verify_validSignature_succeeds() {
        long timestamp = System.currentTimeMillis() / 1000;
        byte[] body = "{\"event\":\"test\"}".getBytes(StandardCharsets.UTF_8);

        Map<String, String> signed = StandardWebhooksVerifier.sign(SECRET, "msg_456", (int) timestamp, body);

        assertTrue(StandardWebhooksVerifier.verify(signed, body, SECRET, timestamp));
    }

    @Test
    void verify_wrongSecret_throws() {
        long timestamp = System.currentTimeMillis() / 1000;
        byte[] body = "{\"event\":\"test\"}".getBytes(StandardCharsets.UTF_8);

        Map<String, String> signed = StandardWebhooksVerifier.sign(SECRET, "msg_789", (int) timestamp, body);

        byte[] wrongSecret = "wrong-secret-key-bytes".getBytes(StandardCharsets.UTF_8);
        assertThrows(StandardWebhooksVerifier.StandardWebhookVerificationException.class,
                () -> StandardWebhooksVerifier.verify(signed, body, wrongSecret, timestamp));
    }

    @Test
    void verify_staleTimestamp_throws() {
        long timestamp = 1000000;
        byte[] body = new byte[0];

        Map<String, String> signed = StandardWebhooksVerifier.sign(SECRET, "msg_old", (int) timestamp, body);

        long now = timestamp + 600;
        assertThrows(StandardWebhooksVerifier.StandardWebhookVerificationException.class,
                () -> StandardWebhooksVerifier.verify(signed, body, SECRET, now));
    }

    @Test
    void verify_missingHeaders_throws() {
        assertThrows(StandardWebhooksVerifier.StandardWebhookVerificationException.class,
                () -> StandardWebhooksVerifier.verify(Map.of(), new byte[0], SECRET, System.currentTimeMillis() / 1000));
    }

    @Test
    void decodeSecret_nonMultipleOf4Lengths() {
        byte[] secret2 = StandardWebhooksVerifier.decodeSecret("whsec_QQ");
        assertEquals(1, secret2.length);
        assertEquals(0x41, secret2[0] & 0xFF);

        byte[] secret3 = StandardWebhooksVerifier.decodeSecret("whsec_QUI");
        assertEquals(2, secret3.length);

        byte[] secret7 = StandardWebhooksVerifier.decodeSecret("whsec_QUJDREV");
        assertEquals(5, secret7.length);

        byte[] secret6 = StandardWebhooksVerifier.decodeSecret("whsec_QUJDRA");
        assertEquals(4, secret6.length);

        byte[] secret13 = StandardWebhooksVerifier.decodeSecret("whsec_QUJDREVGR0hJSktMTQ");
        assertEquals(13, secret13.length);
    }

    @Test
    void decodeSecret_exactMultipleOf4Length() {
        byte[] secret = StandardWebhooksVerifier.decodeSecret("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo1laRYLJ2sFB5pO8Jyk=");
        assertNotNull(secret);
        assertTrue(secret.length > 0);

        byte[] rawSecret = StandardWebhooksVerifier.decodeSecret("MfKQ9r8GKYqrTwjUPD8ILPZIo1laRYLJ2sFB5pO8Jyk=");
        assertArrayEquals(SECRET, rawSecret);

        byte[] exact4 = StandardWebhooksVerifier.decodeSecret("whsec_QUJD");
        assertEquals(3, exact4.length);
    }

    @Test
    void verify_caseInsensitiveHeaders() {
        long timestamp = System.currentTimeMillis() / 1000;
        byte[] body = "{\"event\":\"case\"}".getBytes(StandardCharsets.UTF_8);

        Map<String, String> signed = StandardWebhooksVerifier.sign(SECRET, "msg_case", (int) timestamp, body);
        Map<String, String> lowerHeaders = Map.of(
                "webhook-id", signed.get("webhook-id"),
                "webhook-timestamp", signed.get("webhook-timestamp"),
                "webhook-signature", signed.get("webhook-signature")
        );

        assertTrue(StandardWebhooksVerifier.verify(lowerHeaders, body, SECRET, timestamp));
    }
}