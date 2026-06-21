package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SigningContext;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.adcontextprotocol.adcp.signing.SigningInput;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Rfc9421SignerTest {

    private record TestSigningInput(String method, String targetUri, byte[] body, Map<String, String> headers)
            implements SigningInput {}

    @Test
    void sign_emptyWebhookBody_emitsContentDigest() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] privateKey = keyPair.getPrivate().getEncoded();

        SigningContext context = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        SigningInput input = new TestSigningInput("POST", "https://example.com/webhook",
                new byte[0], headers);

        Rfc9421Signer.SignedOutput output = Rfc9421Signer.sign(context, input,
                "test-kid", "ed25519", privateKey,
                System.currentTimeMillis() / 1000,
                System.currentTimeMillis() / 1000 + 300,
                "test-nonce");

        // content-digest MUST be present even for empty webhook bodies.
        // The digest of byte[0] is a valid required Content-Digest value.
        assertNotNull(output.contentDigestValue(),
                "Empty webhook body must still emit content-digest");
        String expected = ContentDigest.sha256(new byte[0]);
        assertEquals(expected, output.contentDigestValue(),
                "Empty-body digest must be SHA-256 of byte[0]");
    }

    @Test
    void sign_emptyRequestBody_omitsContentDigest() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] privateKey = keyPair.getPrivate().getEncoded();

        SigningContext context = SigningContext.builder(AdcpUse.REQUEST_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        SigningInput input = new TestSigningInput("GET", "https://example.com/path",
                new byte[0], headers);

        Rfc9421Signer.SignedOutput output = Rfc9421Signer.sign(context, input,
                "test-kid", "ed25519", privateKey,
                System.currentTimeMillis() / 1000,
                System.currentTimeMillis() / 1000 + 300,
                "test-nonce");

        // Request signing does not require content-digest for bodyless requests.
        assertNull(output.contentDigestValue(),
                "Empty request body should not emit content-digest");
    }

    @Test
    void sign_ecdsaP384_accepted() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(384);
        KeyPair keyPair = kpg.generateKeyPair();
        byte[] privateKey = keyPair.getPrivate().getEncoded();

        SigningContext context = SigningContext.builder(AdcpUse.REQUEST_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        SigningInput input = new TestSigningInput("POST", "https://example.com/path",
                "{\"x\":1}".getBytes(StandardCharsets.UTF_8), headers);

        // Should not throw — P-384 must be supported.
        Rfc9421Signer.SignedOutput output = assertDoesNotThrow(() ->
                Rfc9421Signer.sign(context, input,
                        "test-kid", "ecdsa-p384-sha384", privateKey,
                        System.currentTimeMillis() / 1000,
                        System.currentTimeMillis() / 1000 + 300,
                        "test-nonce"));

        assertNotNull(output.signatureValue());
        assertTrue(output.signatureValue().startsWith("sig1=:"),
                "Signature header should start with sig1=:");
    }

    @Test
    void sign_unsupportedAlgorithm_throws() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] privateKey = keyPair.getPrivate().getEncoded();

        SigningContext context = SigningContext.builder(AdcpUse.REQUEST_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        SigningInput input = new TestSigningInput("POST", "https://example.com/path",
                "{}".getBytes(StandardCharsets.UTF_8), headers);

        SigningException ex = assertThrows(SigningException.class, () ->
                Rfc9421Signer.sign(context, input,
                        "test-kid", "rsa-sha256", privateKey,
                        System.currentTimeMillis() / 1000,
                        System.currentTimeMillis() / 1000 + 300,
                        "test-nonce"));
        assertTrue(ex.getMessage().contains("Unsupported signing algorithm"),
                "Expected 'Unsupported signing algorithm' message, got: " + ex.getMessage());
    }
}