package org.adcontextprotocol.adcp.server.signing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignatureInputBuilderTest {

    @Test
    void buildsWebhookSignatureInput() {
        String result = SignatureInputBuilder.create()
                .label("sig1")
                .coveredComponents(java.util.List.of(
                        "@method", "@target-uri", "@authority", "content-type", "content-digest"))
                .created(1776520800L)
                .expires(1776521100L)
                .nonce("KXYnfEfJ0PBRZXQyVXfVQA")
                .keyid("test-ed25519-webhook-2026")
                .alg("ed25519")
                .tag("adcp/webhook-signing/v1")
                .build();

        assertEquals(
                "sig1=(\"@method\" \"@target-uri\" \"@authority\" \"content-type\" \"content-digest\");" +
                        "created=1776520800;expires=1776521100;nonce=\"KXYnfEfJ0PBRZXQyVXfVQA\";" +
                        "keyid=\"test-ed25519-webhook-2026\";alg=\"ed25519\";tag=\"adcp/webhook-signing/v1\"",
                result);
    }

    @Test
    void buildsRequestSignatureInput() {
        String result = SignatureInputBuilder.create()
                .label("sig1")
                .coveredComponents(java.util.List.of(
                        "@method", "@target-uri", "@authority", "content-type"))
                .created(1776520800L)
                .expires(1776521100L)
                .nonce("KXYnfEfJ0PBRZXQyVXfVQA")
                .keyid("test-ed25519-2026")
                .alg("ed25519")
                .tag("adcp/request-signing/v1")
                .build();

        assertEquals(
                "sig1=(\"@method\" \"@target-uri\" \"@authority\" \"content-type\");" +
                        "created=1776520800;expires=1776521100;nonce=\"KXYnfEfJ0PBRZXQyVXfVQA\";" +
                        "keyid=\"test-ed25519-2026\";alg=\"ed25519\";tag=\"adcp/request-signing/v1\"",
                result);
    }

    @Test
    void buildsMinimalSignatureInput() {
        String result = SignatureInputBuilder.create()
                .label("sig1")
                .coveredComponents(java.util.List.of("@method"))
                .created(100L)
                .expires(200L)
                .nonce("abc")
                .keyid("key1")
                .alg("ed25519")
                .tag("adcp/webhook-signing/v1")
                .build();

        assertEquals(
                "sig1=(\"@method\");created=100;expires=200;nonce=\"abc\";keyid=\"key1\";alg=\"ed25519\";tag=\"adcp/webhook-signing/v1\"",
                result);
    }
}