package org.adcontextprotocol.adcp.server.signing.jwks;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SignedInput;
import org.adcontextprotocol.adcp.signing.VerificationInput;
import org.adcontextprotocol.adcp.signing.VerificationKeyLookup;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StaticJwksResolverTest {

    private static Map<String, Object> ed25519Jwk() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kid", "test-ed25519");
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("alg", "EdDSA");
        jwk.put("use", "sig");
        jwk.put("key_ops", List.of("verify"));
        jwk.put("adcp_use", "adcp_whk");
        jwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");
        return jwk;
    }

    private static Map<String, Object> es256Jwk() {
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
        return jwk;
    }

    @Test
    void resolveReturnsFoundForKnownKid() {
        Map<String, Map<String, Object>> keys = Map.of(
                "test-ed25519", ed25519Jwk(),
                "test-es256", es256Jwk()
        );
        StaticJwksResolver resolver = new StaticJwksResolver(keys);

        VerificationInput input = new VerificationInput(
                AdcpUse.WEBHOOK_SIGNING,
                "test-ed25519",
                new SignedInput("{}".getBytes(StandardCharsets.UTF_8), Map.of(), "POST", "/")
        );

        VerificationKeyLookup result = resolver.resolve(input);
        assertInstanceOf(VerificationKeyLookup.Found.class, result);
        VerificationKeyLookup.Found found = (VerificationKeyLookup.Found) result;
        assertEquals("test-ed25519", found.key().kid());
        assertEquals("Ed25519", found.key().algorithm());
    }

    @Test
    void resolveReturnsMissingForUnknownKid() {
        Map<String, Map<String, Object>> keys = Map.of(
                "test-ed25519", ed25519Jwk()
        );
        StaticJwksResolver resolver = new StaticJwksResolver(keys);

        VerificationInput input = new VerificationInput(
                AdcpUse.WEBHOOK_SIGNING,
                "nonexistent-kid",
                new SignedInput("{}".getBytes(StandardCharsets.UTF_8), Map.of(), "POST", "/")
        );

        VerificationKeyLookup result = resolver.resolve(input);
        assertInstanceOf(VerificationKeyLookup.Missing.class, result);
        assertEquals("nonexistent-kid", ((VerificationKeyLookup.Missing) result).kid());
    }

    @Test
    void resolveValidatesAdcpUse() {
        Map<String, Object> wrongUseJwk = new LinkedHashMap<>();
        wrongUseJwk.put("kid", "test-req");
        wrongUseJwk.put("kty", "OKP");
        wrongUseJwk.put("crv", "Ed25519");
        wrongUseJwk.put("x", "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA");
        wrongUseJwk.put("adcp_use", "adcp_req");
        wrongUseJwk.put("key_ops", List.of("verify"));

        StaticJwksResolver resolver = new StaticJwksResolver(Map.of("test-req", wrongUseJwk));

        VerificationInput input = new VerificationInput(
                AdcpUse.WEBHOOK_SIGNING,
                "test-req",
                new SignedInput("{}".getBytes(StandardCharsets.UTF_8), Map.of(), "POST", "/")
        );

        JwksResolutionException ex =
                assertThrows(JwksResolutionException.class, () -> resolver.resolve(input));
        assertEquals("webhook_signature_key_purpose_invalid", ex.errorCode());
    }
}