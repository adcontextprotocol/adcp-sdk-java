package org.adcontextprotocol.adcp.server.signing;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeyOriginConsistencyCheckTest {

    @Test
    void check_matchingOrigins_returnsConsistent() {
        KeyOriginCheckResult result = KeyOriginConsistencyCheck.check(
                "https://keys.brand.com/.well-known/jwks.json",
                Map.of("request_signing", "https://keys.brand.com"),
                "request_signing");
        assertInstanceOf(KeyOriginCheckResult.Consistent.class, result);
    }

    @Test
    void check_mismatchedOrigins_returnsInconsistent() {
        KeyOriginCheckResult result = KeyOriginConsistencyCheck.check(
                "https://keys.brand.com/.well-known/jwks.json",
                Map.of("request_signing", "https://keys.attacker.com"),
                "request_signing");
        assertInstanceOf(KeyOriginCheckResult.Inconsistent.class, result);
        KeyOriginCheckResult.Inconsistent inc = (KeyOriginCheckResult.Inconsistent) result;
        assertEquals("request_signature_key_origin_mismatch", inc.errorCode());
        assertTrue(inc.reason().contains("attacker.com"));
    }

    @Test
    void check_missingPurpose_returnsInconsistentWithMissingCode() {
        KeyOriginCheckResult result = KeyOriginConsistencyCheck.check(
                "https://keys.brand.com/.well-known/jwks.json",
                Map.of("webhook_signing", "https://keys.brand.com"),
                "request_signing");
        assertInstanceOf(KeyOriginCheckResult.Inconsistent.class, result);
        KeyOriginCheckResult.Inconsistent inc = (KeyOriginCheckResult.Inconsistent) result;
        assertEquals("request_signature_key_origin_missing", inc.errorCode());
    }

    @Test
    void check_nullKeyOrigins_treatedAsMissing() {
        KeyOriginCheckResult result = KeyOriginConsistencyCheck.check(
                "https://keys.brand.com/.well-known/jwks.json",
                null,
                "request_signing");
        assertInstanceOf(KeyOriginCheckResult.Inconsistent.class, result);
        assertEquals("request_signature_key_origin_missing",
                ((KeyOriginCheckResult.Inconsistent) result).errorCode());
    }

    @Test
    void check_webhookCodeFamily_usesWebhookErrorCodes() {
        KeyOriginCheckResult result = KeyOriginConsistencyCheck.check(
                "https://keys.brand.com/.well-known/jwks.json",
                Map.of("webhook_signing", "https://keys.attacker.com"),
                "webhook_signing",
                "webhook");
        assertInstanceOf(KeyOriginCheckResult.Inconsistent.class, result);
        assertEquals("webhook_signature_key_origin_mismatch",
                ((KeyOriginCheckResult.Inconsistent) result).errorCode());
    }

    @Test
    void check_webhookMissingPurpose_usesWebhookMissingCode() {
        KeyOriginCheckResult result = KeyOriginConsistencyCheck.check(
                "https://keys.brand.com/.well-known/jwks.json",
                null,
                "webhook_signing",
                "webhook");
        assertInstanceOf(KeyOriginCheckResult.Inconsistent.class, result);
        assertEquals("webhook_signature_key_origin_missing",
                ((KeyOriginCheckResult.Inconsistent) result).errorCode());
    }

    @Test
    void check_bareHostInKeyOrigins_matchesUrlHost() {
        KeyOriginCheckResult result = KeyOriginConsistencyCheck.check(
                "https://keys.brand.com/.well-known/jwks.json",
                Map.of("request_signing", "keys.brand.com"),
                "request_signing");
        assertInstanceOf(KeyOriginCheckResult.Consistent.class, result);
    }

    @Test
    void check_defaultCodeFamily_isRequest() {
        KeyOriginCheckResult result = KeyOriginConsistencyCheck.check(
                "https://keys.brand.com/.well-known/jwks.json",
                null,
                "request_signing");
        assertInstanceOf(KeyOriginCheckResult.Inconsistent.class, result);
        assertTrue(((KeyOriginCheckResult.Inconsistent) result).errorCode().startsWith("request_"));
    }

    @Test
    void check_invalidJwksUri_returnsInconsistentMismatch() {
        KeyOriginCheckResult result = KeyOriginConsistencyCheck.check(
                "not-a-valid-url",
                Map.of("request_signing", "https://keys.brand.com"),
                "request_signing");
        assertInstanceOf(KeyOriginCheckResult.Inconsistent.class, result);
    }
}