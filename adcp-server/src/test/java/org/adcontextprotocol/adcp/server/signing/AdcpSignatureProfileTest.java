package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdcpSignatureProfileTest {

    @Test
    void tagForUse_webhook() {
        assertEquals("adcp/webhook-signing/v1", AdcpSignatureProfile.tagForUse(AdcpUse.WEBHOOK_SIGNING));
    }

    @Test
    void tagForUse_request() {
        assertEquals("adcp/request-signing/v1", AdcpSignatureProfile.tagForUse(AdcpUse.REQUEST_SIGNING));
    }

    @Test
    void requiredComponentsForUse_webhook() {
        List<String> components = AdcpSignatureProfile.requiredComponentsForUse(AdcpUse.WEBHOOK_SIGNING);
        assertEquals(List.of("@method", "@target-uri", "@authority", "content-type", "content-digest"), components);
    }

    @Test
    void requiredComponentsForUse_request() {
        List<String> components = AdcpSignatureProfile.requiredComponentsForUse(AdcpUse.REQUEST_SIGNING);
        assertEquals(List.of("@method", "@target-uri", "@authority", "content-type"), components);
    }

    @Test
    void isAlgorithmAllowed_ed25519() {
        assertTrue(AdcpSignatureProfile.isAlgorithmAllowed("ed25519"));
    }

    @Test
    void isAlgorithmAllowed_ecdsa() {
        assertTrue(AdcpSignatureProfile.isAlgorithmAllowed("ecdsa-p256-sha256"));
    }

    @Test
    void isAlgorithmAllowed_rsaRejected() {
        assertFalse(AdcpSignatureProfile.isAlgorithmAllowed("rsa-pss-sha512"));
    }

    @Test
    void validateRequiredComponents_webhookComplete() {
        List<String> components = List.of("@method", "@target-uri", "@authority", "content-type", "content-digest");
        assertNull(AdcpSignatureProfile.validateRequiredComponents(AdcpUse.WEBHOOK_SIGNING, components));
    }

    @Test
    void validateRequiredComponents_webhookMissingContentDigest() {
        List<String> components = List.of("@method", "@target-uri", "@authority", "content-type");
        String error = AdcpSignatureProfile.validateRequiredComponents(AdcpUse.WEBHOOK_SIGNING, components);
        assertEquals("webhook_signature_components_incomplete", error);
    }

    @Test
    void validateRequiredComponents_webhookMissingAuthority() {
        List<String> components = List.of("@method", "@target-uri", "content-type", "content-digest");
        String error = AdcpSignatureProfile.validateRequiredComponents(AdcpUse.WEBHOOK_SIGNING, components);
        assertEquals("webhook_signature_components_incomplete", error);
    }

    @Test
    void validateRequiredComponents_requestComplete() {
        List<String> components = List.of("@method", "@target-uri", "@authority", "content-type");
        assertNull(AdcpSignatureProfile.validateRequiredComponents(AdcpUse.REQUEST_SIGNING, components));
    }

    @Test
    void validateRequiredParams_complete() {
        java.util.Map<String, String> params = java.util.Map.of(
                "created", "1776520800",
                "expires", "1776521100",
                "nonce", "abc",
                "keyid", "key1",
                "alg", "ed25519"
        );
        assertNull(AdcpSignatureProfile.validateRequiredParams(params));
    }

    @Test
    void validateRequiredParams_missingNonce() {
        java.util.Map<String, String> params = java.util.Map.of(
                "created", "1776520800",
                "expires", "1776521100",
                "keyid", "key1",
                "alg", "ed25519"
        );
        String error = AdcpSignatureProfile.validateRequiredParams(params);
        assertEquals("webhook_signature_params_incomplete", error);
    }

    @Test
    void replayWindow() {
        assertEquals(300, AdcpSignatureProfile.REPLAY_WINDOW_SECONDS);
    }
}