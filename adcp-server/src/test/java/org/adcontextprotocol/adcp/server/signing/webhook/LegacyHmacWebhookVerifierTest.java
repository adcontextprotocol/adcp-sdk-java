package org.adcontextprotocol.adcp.server.signing.webhook;

import org.adcontextprotocol.adcp.server.signing.replay.InMemoryReplayStore;
import org.adcontextprotocol.adcp.server.signing.replay.ReplayStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("removal")
class LegacyHmacWebhookVerifierTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREV_SECRET = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);

    private ReplayStore replayStore;
    private LegacyHmacWebhookVerifier verifier;

    @BeforeEach
    void setUp() {
        replayStore = new InMemoryReplayStore();
        verifier = new LegacyHmacWebhookVerifier(replayStore);
    }

    @Test
    void verify_validSignature_returnsValid() {
        long timestamp = System.currentTimeMillis() / 1000;
        byte[] body = "{\"event\":\"test\"}".getBytes(StandardCharsets.UTF_8);
        String hexSig = hmacHex(SECRET, (timestamp + ".").getBytes(StandardCharsets.UTF_8), body);

        Map<String, String> headers = Map.of(
                "X-AdCP-Signature", "sha256=" + hexSig,
                "X-AdCP-Timestamp", String.valueOf(timestamp)
        );

        HmacVerificationResult result = verifier.verify(headers, body, SECRET, null, timestamp);
        assertInstanceOf(HmacVerificationResult.Valid.class, result);
    }

    @Test
    void verify_missingSignatureHeader_returnsInvalid() {
        Map<String, String> headers = Map.of(
                "X-AdCP-Timestamp", "1234567890"
        );
        HmacVerificationResult result = verifier.verify(headers, new byte[0], SECRET, null, 1234567890L);
        assertInstanceOf(HmacVerificationResult.Invalid.class, result);
        assertEquals("missing_header", ((HmacVerificationResult.Invalid) result).errorCode());
    }

    @Test
    void verify_missingTimestampHeader_returnsInvalid() {
        Map<String, String> headers = Map.of(
                "X-AdCP-Signature", "sha256=abc"
        );
        HmacVerificationResult result = verifier.verify(headers, new byte[0], SECRET, null, 1234567890L);
        assertInstanceOf(HmacVerificationResult.Invalid.class, result);
        assertEquals("missing_header", ((HmacVerificationResult.Invalid) result).errorCode());
    }

    @Test
    void verify_invalidFormat_returnsInvalid() {
        Map<String, String> headers = Map.of(
                "X-AdCP-Signature", "hmac=abc",
                "X-AdCP-Timestamp", "1234567890"
        );
        HmacVerificationResult result = verifier.verify(headers, new byte[0], SECRET, null, 1234567890L);
        assertInstanceOf(HmacVerificationResult.Invalid.class, result);
        assertEquals("invalid_format", ((HmacVerificationResult.Invalid) result).errorCode());
    }

    @Test
    void verify_staleTimestamp_returnsStaleTimestamp() {
        long timestamp = 1000000;
        byte[] body = new byte[0];
        String hexSig = hmacHex(SECRET, (timestamp + ".").getBytes(StandardCharsets.UTF_8), body);

        Map<String, String> headers = Map.of(
                "X-AdCP-Signature", "sha256=" + hexSig,
                "X-AdCP-Timestamp", String.valueOf(timestamp)
        );

        long now = timestamp + 600;
        HmacVerificationResult result = verifier.verify(headers, body, SECRET, null, now);
        assertInstanceOf(HmacVerificationResult.StaleTimestamp.class, result);
    }

    @Test
    void verify_wrongSignature_returnsInvalid() {
        long timestamp = System.currentTimeMillis() / 1000;
        Map<String, String> headers = Map.of(
                "X-AdCP-Signature", "sha256=0000000000000000000000000000000000000000000000000000000000000000",
                "X-AdCP-Timestamp", String.valueOf(timestamp)
        );
        HmacVerificationResult result = verifier.verify(headers, new byte[0], SECRET, null, timestamp);
        assertInstanceOf(HmacVerificationResult.Invalid.class, result);
        assertEquals("signature_mismatch", ((HmacVerificationResult.Invalid) result).errorCode());
    }

    @Test
    void verify_shortSecret_returnsSecretNotFound() {
        byte[] shortSecret = "short".getBytes(StandardCharsets.UTF_8);
        HmacVerificationResult result = verifier.verify(
                Map.of("X-AdCP-Signature", "sha256=abc", "X-AdCP-Timestamp", "123"),
                new byte[0], shortSecret, null, 123);
        assertInstanceOf(HmacVerificationResult.SecretNotFound.class, result);
    }

    @Test
    void verify_secretRotation_withPreviousSecret() {
        long timestamp = System.currentTimeMillis() / 1000;
        byte[] body = "{\"event\":\"rotate\"}".getBytes(StandardCharsets.UTF_8);
        String hexSig = hmacHex(PREV_SECRET, (timestamp + ".").getBytes(StandardCharsets.UTF_8), body);

        Map<String, String> headers = Map.of(
                "X-AdCP-Signature", "sha256=" + hexSig,
                "X-AdCP-Timestamp", String.valueOf(timestamp)
        );

        HmacVerificationResult result = verifier.verify(headers, body, SECRET, PREV_SECRET, timestamp);
        assertInstanceOf(HmacVerificationResult.Valid.class, result);
    }

    @Test
    void verify_caseInsensitiveHeaders() {
        long timestamp = System.currentTimeMillis() / 1000;
        byte[] body = new byte[0];
        String hexSig = hmacHex(SECRET, (timestamp + ".").getBytes(StandardCharsets.UTF_8), body);

        Map<String, String> headers = Map.of(
                "x-adcp-signature", "sha256=" + hexSig,
                "x-adcp-timestamp", String.valueOf(timestamp)
        );

        HmacVerificationResult result = verifier.verify(headers, body, SECRET, null, timestamp);
        assertInstanceOf(HmacVerificationResult.Valid.class, result);
    }

    private static String hmacHex(byte[] secret, byte[] prefix, byte[] body) {
        try {
            byte[] message = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, message, 0, prefix.length);
            System.arraycopy(body, 0, message, prefix.length, body.length);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] hash = mac.doFinal(message);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}