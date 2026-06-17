package org.adcontextprotocol.adcp.server.signing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ContentDigestTest {

    @Test
    void sha256_knownBody() {
        // From webhook test vector 001: body has Content-Digest sha-256=:dJ2koiIMZIhdGE7tidErCHV13FFvOIowCcXDiwyG54I=:
        String body = "{\"idempotency_key\":\"whk_01HW9D3H8FZP2N6R8T0V4X6Z9B\",\"task_id\":\"task_456\",\"operation_id\":\"op_abc\",\"status\":\"completed\",\"result\":{\"media_buy_id\":\"mb_001\"}}";
        String result = ContentDigest.sha256(body.getBytes(StandardCharsets.UTF_8));
        assertEquals("sha-256=:dJ2koiIMZIhdGE7tidErCHV13FFvOIowCcXDiwyG54I=:", result);
    }

    @Test
    void sha256_emptyBody() {
        String result = ContentDigest.sha256("".getBytes(StandardCharsets.UTF_8));
        assertTrue(result.startsWith("sha-256=:"));
        assertTrue(result.endsWith(":"));
        // Standard base64 format: may contain +, /, and = padding
        String digest = result.substring("sha-256=:".length(), result.length() - 1);
        assertFalse(digest.isEmpty());
    }

    @Test
    void sha512_knownBody() {
        String body = "hello";
        String result = ContentDigest.sha512(body.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.startsWith("sha-512=:"));
        assertTrue(result.endsWith(":"));
    }

    @Test
    void unsupportedAlgorithmThrows() {
        assertThrows(IllegalArgumentException.class, () -> ContentDigest.compute("sha-1", "test".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void base64UrlNoPadding_noPadding() {
        byte[] data = new byte[]{0x01, 0x02, 0x03};
        String encoded = ContentDigest.base64UrlNoPadding(data);
        assertFalse(encoded.contains("="));
        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("/"));
    }

    @Test
    void base64UrlNoPadding_roundTrip() {
        byte[] data = "test data for encoding".getBytes(StandardCharsets.UTF_8);
        String encoded = ContentDigest.base64UrlNoPadding(data);
        byte[] decoded = java.util.Base64.getUrlDecoder().decode(encoded);
        assertArrayEquals(data, decoded);
    }
}