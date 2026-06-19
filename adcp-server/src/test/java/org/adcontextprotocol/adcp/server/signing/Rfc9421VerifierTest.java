package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Rfc9421VerifierTest {

    private KeyPair ed25519KeyPair;
    private VerificationKey verificationKey;

    @BeforeEach
    void setUp() {
        ed25519KeyPair = InProcessKeyGenerator.generateEd25519();
        verificationKey = new VerificationKey(
                "test-key", "Ed25519", ed25519KeyPair.getPublic().getEncoded(), "Ed25519");
    }

    private long created = System.currentTimeMillis() / 1000;
    private long expires = created + 300;

    private Map<String, String> baseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("content-digest", ContentDigest.sha256("{}".getBytes()));
        return headers;
    }

    private String validSignatureInput() {
        return "sig1=(\"@method\" \"@target-uri\" \"@authority\" \"content-type\" \"content-digest\");created="
                + created + ";expires=" + expires
                + ";nonce=\"test-nonce\";keyid=\"test-key\";alg=\"ed25519\";tag=\"adcp/webhook-signing/v1\"";
    }

    @Test
    void verify_malformedCreated_returnsInvalid() {
        Map<String, String> headers = baseHeaders();
        headers.put("signature-input", "sig1=(\"@method\" \"@target-uri\" \"@authority\" \"content-type\" \"content-digest\");created=not-a-number;expires="
                + expires + ";nonce=\"test-nonce\";keyid=\"test-key\";alg=\"ed25519\";tag=\"adcp/webhook-signing/v1\"");
        headers.put("signature", "sig1=:AAAA:");

        SignedInput input = new SignedInput("{}".getBytes(), headers, "POST", "https://example.com/webhook");
        VerificationResult result = Rfc9421Verifier.verify(input, verificationKey, AdcpUse.WEBHOOK_SIGNING, created + 150);

        assertInstanceOf(VerificationResult.Invalid.class, result);
        VerificationResult.Invalid invalid = (VerificationResult.Invalid) result;
        assertTrue(invalid.errorCode().contains("params_incomplete"),
                "Expected params_incomplete error code, got: " + invalid.errorCode());
    }

    @Test
    void verify_malformedExpires_returnsInvalid() {
        Map<String, String> headers = baseHeaders();
        headers.put("signature-input", "sig1=(\"@method\" \"@target-uri\" \"@authority\" \"content-type\" \"content-digest\");created="
                + created + ";expires=not-a-number;nonce=\"test-nonce\";keyid=\"test-key\";alg=\"ed25519\";tag=\"adcp/webhook-signing/v1\"");
        headers.put("signature", "sig1=:AAAA:");

        SignedInput input = new SignedInput("{}".getBytes(), headers, "POST", "https://example.com/webhook");
        VerificationResult result = Rfc9421Verifier.verify(input, verificationKey, AdcpUse.WEBHOOK_SIGNING, created + 150);

        assertInstanceOf(VerificationResult.Invalid.class, result);
        VerificationResult.Invalid invalid = (VerificationResult.Invalid) result;
        assertTrue(invalid.errorCode().contains("params_incomplete"),
                "Expected params_incomplete error code, got: " + invalid.errorCode());
    }

    @Test
    void verify_malformedSignatureBase64_returnsInvalid() {
        Map<String, String> headers = baseHeaders();
        headers.put("signature-input", validSignatureInput());
        headers.put("signature", "sig1=:!!!:");

        SignedInput input = new SignedInput("{}".getBytes(), headers, "POST", "https://example.com/webhook");
        VerificationResult result = Rfc9421Verifier.verify(input, verificationKey, AdcpUse.WEBHOOK_SIGNING, created + 150);

        assertInstanceOf(VerificationResult.Invalid.class, result);
        VerificationResult.Invalid invalid = (VerificationResult.Invalid) result;
        assertTrue(invalid.errorCode().contains("header_malformed"),
                "Expected header_malformed error code, got: " + invalid.errorCode());
    }
}