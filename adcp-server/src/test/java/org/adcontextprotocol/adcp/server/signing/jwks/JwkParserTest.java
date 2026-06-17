package org.adcontextprotocol.adcp.server.signing.jwks;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.VerificationException;
import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JwkParserTest {

    @Test
    void parseEd25519Jwk() throws Exception {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-ed25519");
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("alg", "EdDSA");
        jwk.put("use", "sig");
        jwk.put("key_ops", List.of("verify"));
        jwk.put("adcp_use", "adcp_whk");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");

        VerificationKey key = JwkParser.parse(jwk, AdcpUse.WEBHOOK_SIGNING);

        assertEquals("test-ed25519", key.kid());
        assertEquals("Ed25519", key.algorithm());
        assertEquals("Ed25519", key.crv());
        assertNotNull(key.publicKeyBytes());
        assertEquals(32 + 12 + 2, key.publicKeyBytes().length); // raw key + SPKI overhead
    }

    @Test
    void parseEs256Jwk() throws Exception {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-es256");
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("alg", "ES256");
        jwk.put("use", "sig");
        jwk.put("key_ops", List.of("verify"));
        jwk.put("adcp_use", "adcp_whk");
        jwk.put("x", "0X7G_jryFpiX9XO3CKxIqUQs3DC8OhUkw6Rb5QOZd5M");
        jwk.put("y", "MwZN7qQJzLpTD5dyDJAoqOLZJ9r8-GCh4BnOYu6NE0c");

        VerificationKey key = JwkParser.parse(jwk, AdcpUse.WEBHOOK_SIGNING);

        assertEquals("test-es256", key.kid());
        assertEquals("EC", key.algorithm());
        assertEquals("P-256", key.crv());
        assertNotNull(key.publicKeyBytes());
        assertTrue(key.publicKeyBytes().length > 64, "EC public key DER should be larger than raw coordinates");
    }

    @Test
    void parseEs384Jwk() throws Exception {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-es384");
        jwk.put("kty", "EC");
        jwk.put("crv", "P-384");
        jwk.put("x", "4KWtfAj7L6N1FR3Nk9gr8P2LmN6zW5O0YXZ2vH1i5q3w");
        jwk.put("y", "q2R5fN7k8m3Lp4Vv9W0yXz1hA6bC3cE8fD2gJ7iK4s6");
        jwk.put("use", "sig");
        jwk.put("key_ops", List.of("verify"));
        jwk.put("adcp_use", "adcp_whk");

        VerificationKey key = JwkParser.parse(jwk, AdcpUse.WEBHOOK_SIGNING);
        assertEquals("test-es384", key.kid());
        assertEquals("EC", key.algorithm());
        assertEquals("P-384", key.crv());
    }

    @Test
    void parseRsaJwk() throws Exception {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-rsa");
        jwk.put("kty", "RSA");
        jwk.put("alg", "RS256");
        jwk.put("use", "sig");
        jwk.put("key_ops", List.of("verify"));
        jwk.put("adcp_use", "adcp_whk");
        jwk.put("n", "vqyCtx9k5J8Y8XNSEl0d3eXxEsT5FK0JfL7mVb0X2qS3kH1vJ9tY8Z5pA6sD4fB2gC7iK0mN3oL8wR1uP5vQ3xS6yT9kL2jH4fN7pA0bM6cE1dK8qF3sV5wY2zT9gR7iL4oP6xA0mC3vS8kN1fW5yB2qE7tR4j");
        jwk.put("e", "AQAB");

        VerificationKey key = JwkParser.parse(jwk, AdcpUse.WEBHOOK_SIGNING);
        assertEquals("test-rsa", key.kid());
        assertEquals("RSA", key.algorithm());
        assertNull(key.crv());
    }

    @Test
    void adcpUseMismatchThrowsException() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-wrong-purpose");
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");
        jwk.put("adcp_use", "adcp_req");
        jwk.put("key_ops", List.of("verify"));

        VerificationException ex = assertThrows(VerificationException.class,
                () -> JwkParser.parse(jwk, AdcpUse.WEBHOOK_SIGNING));
        assertEquals("webhook_signature_key_purpose_invalid", ex.errorCode());
        assertTrue(ex.getMessage().contains("adcp_req"));
    }

    @Test
    void adcpUseMatchesExpectedUse() throws Exception {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-req-signing");
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");
        jwk.put("adcp_use", "adcp_req");
        jwk.put("key_ops", List.of("verify"));

        VerificationKey key = JwkParser.parse(jwk, AdcpUse.REQUEST_SIGNING);
        assertEquals("test-req-signing", key.kid());
    }

    @Test
    void missingAdcpUseWhenExpectedThrowsException() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-no-adcp-use");
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");

        VerificationException ex = assertThrows(VerificationException.class,
                () -> JwkParser.parse(jwk, AdcpUse.WEBHOOK_SIGNING));
        assertEquals("webhook_signature_key_purpose_invalid", ex.errorCode());
    }

    @Test
    void nullExpectedUseSkipsAdcpUseValidation() throws Exception {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-no-validation");
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");

        VerificationKey key = JwkParser.parse(jwk, null);
        assertEquals("test-no-validation", key.kid());
    }

    @Test
    void keyOpsWithoutVerifyThrowsException() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-no-verify");
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");
        jwk.put("key_ops", List.of("encrypt"));

        VerificationException ex = assertThrows(VerificationException.class,
                () -> JwkParser.parse(jwk, null));
        assertEquals("webhook_signature_key_purpose_invalid", ex.errorCode());
        assertTrue(ex.getMessage().contains("verify"));
    }

    @Test
    void keyOpsWithVerifyPasses() throws Exception {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-with-verify");
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");
        jwk.put("key_ops", List.of("sign", "verify"));

        VerificationKey key = JwkParser.parse(jwk, null);
        assertEquals("test-with-verify", key.kid());
    }

    @Test
    void missingKidThrowsException() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");

        VerificationException ex = assertThrows(VerificationException.class,
                () -> JwkParser.parse(jwk, null));
        assertEquals("webhook_signature_invalid", ex.errorCode());
        assertTrue(ex.getMessage().contains("kid"));
    }

    @Test
    void missingKtyThrowsException() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-missing-kty");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");

        VerificationException ex = assertThrows(VerificationException.class,
                () -> JwkParser.parse(jwk, null));
        assertEquals("webhook_signature_invalid", ex.errorCode());
        assertTrue(ex.getMessage().contains("kty"));
    }

    @Test
    void unsupportedKtyThrowsException() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-unsupported-kty");
        jwk.put("kty", "oct");
        jwk.put("k", "c2VjcmV0");

        VerificationException ex = assertThrows(VerificationException.class,
                () -> JwkParser.parse(jwk, null));
        assertEquals("webhook_signature_invalid", ex.errorCode());
        assertTrue(ex.getMessage().contains("oct"));
    }

    @Test
    void unsupportedOkpCurveThrowsException() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-unsupported-curve");
        jwk.put("kty", "OKP");
        jwk.put("crv", "X25519");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");

        VerificationException ex = assertThrows(VerificationException.class,
                () -> JwkParser.parse(jwk, null));
        assertEquals("webhook_signature_invalid", ex.errorCode());
        assertTrue(ex.getMessage().contains("X25519"));
    }
}