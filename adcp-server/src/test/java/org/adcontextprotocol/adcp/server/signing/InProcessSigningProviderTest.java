package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InProcessSigningProviderTest {

    private KeyPair ed25519KeyPair;
    private KeyPair es256KeyPair;

    @BeforeEach
    void setUp() {
        ed25519KeyPair = InProcessKeyGenerator.generateEd25519();
        es256KeyPair = InProcessKeyGenerator.generateES256();
    }

    @Test
    void ed25519_signAndVerifyRoundTrip() throws SigningException {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();
        PublicKey publicKey = ed25519KeyPair.getPublic();

        InProcessSigningProvider signer = new InProcessSigningProvider(
                privateKey, "test-ed25519-key", AdcpSignatureProfile.ALG_ED25519, "Ed25519");

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        byte[] body = "{\"task_id\":\"123\"}".getBytes(StandardCharsets.UTF_8);
        SigningInput input = new TestSigningInput("POST", "https://buyer.example.com/webhook", body, headers);

        Signature sig = signer.sign(context, input);

        assertEquals("sig1", sig.label());
        assertEquals("test-ed25519-key", sig.kid());
        assertEquals(AdcpSignatureProfile.ALG_ED25519, sig.algorithm());
        assertNotNull(sig.signatureBytes());
        assertNotNull(sig.signatureInput());
        assertTrue(sig.signatureInput().contains("alg=\"ed25519\""));
        assertTrue(sig.signatureInput().contains("tag=\"adcp/webhook-signing/v1\""));
    }

    @Test
    void es256_signAndVerifyRoundTrip() throws SigningException {
        PrivateKey privateKey = es256KeyPair.getPrivate();

        InProcessSigningProvider signer = new InProcessSigningProvider(
                privateKey, "test-es256-key", AdcpSignatureProfile.ALG_ECDSA_P256_SHA256, "P-256");

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        byte[] body = "{\"task_id\":\"456\"}".getBytes(StandardCharsets.UTF_8);
        SigningInput input = new TestSigningInput("POST", "https://buyer.example.com/webhook", body, headers);

        Signature sig = signer.sign(context, input);

        assertEquals("sig1", sig.label());
        assertEquals("test-es256-key", sig.kid());
        assertEquals(AdcpSignatureProfile.ALG_ECDSA_P256_SHA256, sig.algorithm());
        assertTrue(sig.signatureInput().contains("alg=\"ecdsa-p256-sha256\""));
    }

    @Test
    void signWithExplicitTimestamps() throws SigningException {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();

        InProcessSigningProvider signer = new InProcessSigningProvider(
                privateKey, "test-ed25519-key", AdcpSignatureProfile.ALG_ED25519, "Ed25519");

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        byte[] body = "{\"task_id\":\"789\"}".getBytes(StandardCharsets.UTF_8);
        SigningInput input = new TestSigningInput("POST", "https://buyer.example.com/webhook", body, headers);

        long created = 1776520800L;
        long expires = created + 300;
        String nonce = "test-nonce-12345";

        Signature sig = signer.sign(context, input, created, expires, nonce);

        assertTrue(sig.signatureInput().contains("created=1776520800"));
        assertTrue(sig.signatureInput().contains("expires=1776521100"));
        assertTrue(sig.signatureInput().contains("nonce=\"test-nonce-12345\""));
    }

    @Test
    void sign_requestSigningUse() throws SigningException {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();

        InProcessSigningProvider signer = new InProcessSigningProvider(
                privateKey, "test-req-key", AdcpSignatureProfile.ALG_ED25519, "Ed25519");

        SigningContext context = SigningContext.builder(AdcpUse.REQUEST_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        byte[] body = new byte[0];
        SigningInput input = new TestSigningInput("POST", "https://seller.example.com/adcp/v3/endpoint", body, headers);

        Signature sig = signer.sign(context, input);

        assertTrue(sig.signatureInput().contains("tag=\"adcp/request-signing/v1\""));
    }

    @Test
    void sign_addsContentDigestForNonEmptyBody() throws SigningException {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();

        InProcessSigningProvider signer = new InProcessSigningProvider(
                privateKey, "test-ed25519-key", AdcpSignatureProfile.ALG_ED25519, "Ed25519");

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        byte[] body = "{\"test\":true}".getBytes(StandardCharsets.UTF_8);
        SigningInput input = new TestSigningInput("POST", "https://buyer.example.com/webhook", body, headers);

        Signature sig = signer.sign(context, input);

        assertTrue(sig.signatureInput().contains("content-digest"));
    }

    @Test
    void verificationRoundTrip_ed25519() throws SigningException {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();
        PublicKey publicKey = ed25519KeyPair.getPublic();

        InProcessSigningProvider signer = new InProcessSigningProvider(
                privateKey, "test-ed25519-roundtrip", AdcpSignatureProfile.ALG_ED25519, "Ed25519");

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        byte[] body = "{\"round\":\"trip\"}".getBytes(StandardCharsets.UTF_8);
        SigningInput signingInput = new TestSigningInput("POST", "https://buyer.example.com/webhook", body, headers);

        long created = System.currentTimeMillis() / 1000;
        long expires = created + 300;
        String nonce = "roundtrip-nonce-test";

        Signature sig = signer.sign(context, signingInput, created, expires, nonce);

        Map<String, String> verifyHeaders = new LinkedHashMap<>(headers);
        verifyHeaders.put("signature-input", sig.signatureInput());
        verifyHeaders.put("signature", sig.label() + "=:" + ContentDigest.base64UrlNoPadding(sig.signatureBytes()) + ":");
        verifyHeaders.put("content-digest", ContentDigest.sha256(body));

        SignedInput verifyInput = new SignedInput(body, verifyHeaders, "POST", "https://buyer.example.com/webhook");

        VerificationKey vKey = new VerificationKey("test-ed25519-roundtrip", "Ed25519", publicKey.getEncoded(), "Ed25519");

        VerificationResult result = InProcessVerificationProvider.verify(verifyInput, vKey, AdcpUse.WEBHOOK_SIGNING, created + 150);

        assertInstanceOf(VerificationResult.Valid.class, result, "Expected Valid result, got: " + result);
        assertEquals("test-ed25519-roundtrip", ((VerificationResult.Valid) result).kid());
    }

    @Test
    void verificationRoundTrip_es256() throws SigningException {
        PrivateKey privateKey = es256KeyPair.getPrivate();
        PublicKey publicKey = es256KeyPair.getPublic();

        InProcessSigningProvider signer = new InProcessSigningProvider(
                privateKey, "test-es256-roundtrip", AdcpSignatureProfile.ALG_ECDSA_P256_SHA256, "P-256");

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        byte[] body = "{\"round\":\"trip\",\"alg\":\"es256\"}".getBytes(StandardCharsets.UTF_8);
        SigningInput signingInput = new TestSigningInput("POST", "https://buyer.example.com/webhook", body, headers);

        long created = System.currentTimeMillis() / 1000;
        long expires = created + 300;
        String nonce = "es256-roundtrip-nonce";

        Signature sig = signer.sign(context, signingInput, created, expires, nonce);

        Map<String, String> verifyHeaders = new LinkedHashMap<>(headers);
        verifyHeaders.put("signature-input", sig.signatureInput());
        verifyHeaders.put("signature", sig.label() + "=:" + ContentDigest.base64UrlNoPadding(sig.signatureBytes()) + ":");
        verifyHeaders.put("content-digest", ContentDigest.sha256(body));

        SignedInput verifyInput = new SignedInput(body, verifyHeaders, "POST", "https://buyer.example.com/webhook");

        VerificationKey vKey = new VerificationKey("test-es256-roundtrip", "EC", publicKey.getEncoded(), "P-256");

        VerificationResult result = InProcessVerificationProvider.verify(verifyInput, vKey, AdcpUse.WEBHOOK_SIGNING, created + 150);

        assertInstanceOf(VerificationResult.Valid.class, result, "Expected Valid result, got: " + result);
        assertEquals("test-es256-roundtrip", ((VerificationResult.Valid) result).kid());
    }

    @Test
    void verificationFails_withWrongKey() throws SigningException {
        PrivateKey privateKey = ed25519KeyPair.getPrivate();
        KeyPair otherKeyPair = InProcessKeyGenerator.generateEd25519();

        InProcessSigningProvider signer = new InProcessSigningProvider(
                privateKey, "test-ed25519-wrong", AdcpSignatureProfile.ALG_ED25519, "Ed25519");

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");

        byte[] body = "{\"wrong\":\"key\"}".getBytes(StandardCharsets.UTF_8);
        SigningInput signingInput = new TestSigningInput("POST", "https://buyer.example.com/webhook", body, headers);

        long created = System.currentTimeMillis() / 1000;
        long expires = created + 300;
        String nonce = "wrong-key-nonce";

        Signature sig = signer.sign(context, signingInput, created, expires, nonce);

        Map<String, String> verifyHeaders = new LinkedHashMap<>(headers);
        verifyHeaders.put("signature-input", sig.signatureInput());
        verifyHeaders.put("signature", sig.label() + "=:" + ContentDigest.base64UrlNoPadding(sig.signatureBytes()) + ":");
        verifyHeaders.put("content-digest", ContentDigest.sha256(body));

        SignedInput verifyInput = new SignedInput(body, verifyHeaders, "POST", "https://buyer.example.com/webhook");

        VerificationKey vKey = new VerificationKey("test-ed25519-wrong", "Ed25519", otherKeyPair.getPublic().getEncoded(), "Ed25519");

        VerificationResult result = InProcessVerificationProvider.verify(verifyInput, vKey, AdcpUse.WEBHOOK_SIGNING, created + 150);

        assertInstanceOf(VerificationResult.Invalid.class, result);
        assertTrue(((VerificationResult.Invalid) result).errorCode().contains("invalid"));
    }

    private record TestSigningInput(String method, String targetUri, byte[] body, Map<String, String> headers)
            implements SigningInput {}
}