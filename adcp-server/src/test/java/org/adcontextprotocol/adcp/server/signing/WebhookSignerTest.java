package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignerTest {

    private KeyPair ed25519KeyPair;
    private KeyPair es256KeyPair;

    @BeforeEach
    void setUp() {
        ed25519KeyPair = InProcessKeyGenerator.generateEd25519();
        es256KeyPair = InProcessKeyGenerator.generateES256();
    }

    @Test
    void defaultWebhookSigner_producesSignatureHeaders() {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();
        InProcessSigningProvider signingProvider = new InProcessSigningProvider(
                privateKey, "test-whk-key", AdcpSignatureProfile.ALG_ED25519, "Ed25519");
        DefaultWebhookSigner signer = new DefaultWebhookSigner(signingProvider);

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        byte[] body = "{\"event\":\"test\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        WebhookSigningResult result = signer.sign(context, "POST", "https://buyer.example.com/webhook", body, headers);

        assertNotNull(result.signatureInput());
        assertNotNull(result.signature());
        assertNotNull(result.contentDigest());
        assertTrue(result.signatureInput().contains("tag=\"adcp/webhook-signing/v1\""));
        assertTrue(result.signatureInput().contains("keyid=\"test-whk-key\""));
        assertTrue(result.signatureInput().contains("alg=\"ed25519\""));
    }

    @Test
    void defaultWebhookSigner_includesContentDigest() {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();
        InProcessSigningProvider signingProvider = new InProcessSigningProvider(
                privateKey, "test-whk-key", AdcpSignatureProfile.ALG_ED25519, "Ed25519");
        DefaultWebhookSigner signer = new DefaultWebhookSigner(signingProvider);

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        byte[] body = "{\"event\":\"test\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        WebhookSigningResult result = signer.sign(context, "POST", "https://buyer.example.com/webhook", body, headers);

        assertNotNull(result.contentDigest());
        assertTrue(result.contentDigest().startsWith("sha-256=:"));
    }

    @Test
    void defaultWebhookSigner_rejectsRequestSigningContext() {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();
        InProcessSigningProvider signingProvider = new InProcessSigningProvider(
                privateKey, "test-req-key", AdcpSignatureProfile.ALG_ED25519, "Ed25519");
        DefaultWebhookSigner signer = new DefaultWebhookSigner(signingProvider);

        SigningContext requestContext = SigningContext.builder(AdcpUse.REQUEST_SIGNING).build();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () ->
                signer.sign(requestContext, "POST", "https://example.com/api", body, Map.of()));
    }

    @Test
    void defaultWebhookSigner_es256() {
        PrivateKey privateKey = es256KeyPair.getPrivate();
        InProcessSigningProvider signingProvider = new InProcessSigningProvider(
                privateKey, "test-es256-whk-key", AdcpSignatureProfile.ALG_ECDSA_P256_SHA256, "P-256");
        DefaultWebhookSigner signer = new DefaultWebhookSigner(signingProvider);

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        byte[] body = "{\"event\":\"es256_test\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        WebhookSigningResult result = signer.sign(context, "POST", "https://buyer.example.com/webhook", body, headers);

        assertNotNull(result.signatureInput());
        assertTrue(result.signatureInput().contains("alg=\"ecdsa-p256-sha256\""));
    }

    @Test
    void sign_emptyBody_includesContentDigest() {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();
        InProcessSigningProvider signingProvider = new InProcessSigningProvider(
                privateKey, "test-whk-key", AdcpSignatureProfile.ALG_ED25519, "Ed25519");
        DefaultWebhookSigner signer = new DefaultWebhookSigner(signingProvider);

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        byte[] emptyBody = new byte[0];
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        WebhookSigningResult result = signer.sign(context, "POST", "https://buyer.example.com/webhook", emptyBody, headers);

        assertNotNull(result.contentDigest(), "Empty body must produce a non-null Content-Digest");
        assertEquals("sha-256=:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=:", result.contentDigest());
    }

    @Test
    void sign_nullBody_doesNotIncludeContentDigest() {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();
        InProcessSigningProvider signingProvider = new InProcessSigningProvider(
                privateKey, "test-whk-key", AdcpSignatureProfile.ALG_ED25519, "Ed25519");
        DefaultWebhookSigner signer = new DefaultWebhookSigner(signingProvider);

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        assertThrows(RuntimeException.class, () ->
                signer.sign(context, "POST", "https://buyer.example.com/webhook", null, headers));
    }

    @Test
    void webhookSigner_roundTripVerification() throws SigningException {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();
        PublicKey publicKey = ed25519KeyPair.getPublic();

        InProcessSigningProvider signingProvider = new InProcessSigningProvider(
                privateKey, "test-roundtrip-key", AdcpSignatureProfile.ALG_ED25519, "Ed25519");
        DefaultWebhookSigner signer = new DefaultWebhookSigner(signingProvider);

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        byte[] body = "{\"task_id\":\"round-trip\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        WebhookSigningResult result = signer.sign(context, "POST", "https://buyer.example.com/webhook", body, headers);

        Map<String, String> verifyHeaders = new LinkedHashMap<>();
        verifyHeaders.put("content-type", "application/json");
        verifyHeaders.put("signature-input", result.signatureInput());
        verifyHeaders.put("signature", result.signature());
        if (result.contentDigest() != null) {
            verifyHeaders.put("content-digest", result.contentDigest());
        }

        SignedInput verifyInput = new SignedInput(body, verifyHeaders, "POST", "https://buyer.example.com/webhook");

        VerificationKey vKey = new VerificationKey("test-roundtrip-key", "Ed25519", publicKey.getEncoded(), "Ed25519");

        long referenceNow = System.currentTimeMillis() / 1000 + 150;
        VerificationResult vr = InProcessVerificationProvider.verify(verifyInput, vKey, AdcpUse.WEBHOOK_SIGNING, referenceNow);

        assertInstanceOf(VerificationResult.Valid.class, vr);
    }
}