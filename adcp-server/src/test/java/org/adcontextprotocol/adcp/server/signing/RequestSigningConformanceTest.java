package org.adcontextprotocol.adcp.server.signing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SignedInput;
import org.adcontextprotocol.adcp.signing.VerificationException;
import org.adcontextprotocol.adcp.signing.VerificationInput;
import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.adcontextprotocol.adcp.signing.VerificationKeyLookup;
import org.adcontextprotocol.adcp.server.signing.jwks.JwkParser;
import org.adcontextprotocol.adcp.server.signing.jwks.JwksResolutionException;
import org.adcontextprotocol.adcp.server.signing.jwks.StaticJwksResolver;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for the committed AdCP request-signing negative test vectors.
 *
 * <p>Drives the verifier + JWKS resolver against the JSON vectors in
 * {@code src/test/resources/compliance/request-signing/negative/} and asserts
 * that each produces the exact {@code expected_outcome.error_code}.
 */
class RequestSigningConformanceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static final String[] NEGATIVE_VECTORS = {
            "/compliance/request-signing/negative/021-duplicate-signature-input-label.json",
            "/compliance/request-signing/negative/024-unquoted-string-param.json",
            "/compliance/request-signing/negative/025-jwk-alg-crv-mismatch.json"
    };

    @TestFactory
    Stream<DynamicTest> negativeVectorsProduceExpectedErrorCodes() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (String resourcePath : NEGATIVE_VECTORS) {
            JsonNode vector = loadResource(resourcePath);
            String name = vector.get("name").asText();
            String expectedErrorCode = vector.get("expected_outcome").get("error_code").asText();
            long referenceNow = vector.get("reference_now").asLong();

            tests.add(DynamicTest.dynamicTest("negative_" + sanitize(name), () -> {
                VerificationResult result = runVector(vector, referenceNow);
                assertInstanceOf(VerificationResult.Invalid.class, result,
                        "Expected Invalid result for: " + name);
                VerificationResult.Invalid invalid = (VerificationResult.Invalid) result;
                assertEquals(expectedErrorCode, invalid.errorCode(),
                        "Error code mismatch for: " + name);
            }));
        }
        return tests.stream();
    }

    private VerificationResult runVector(JsonNode vector, long referenceNow) throws IOException {
        JsonNode request = vector.get("request");
        String method = request.get("method").asText();
        String url = request.get("url").asText();
        JsonNode headersNode = request.get("headers");

        Map<String, String> headers = new LinkedHashMap<>();
        headersNode.properties().forEach(entry ->
                headers.put(HeaderNormalizer.normalizeName(entry.getKey()), entry.getValue().asText()));

        String body = request.has("body") ? request.get("body").asText() : "";
        byte[] bodyBytes = body.getBytes();

        SignedInput signedInput = new SignedInput(bodyBytes, headers, method, url);

        // Build the JWKS from jwks_override or jwks_ref (keys.json).
        Map<String, Map<String, Object>> jwksByKeyId = loadJwks(vector);

        // Resolve the verification key via StaticJwksResolver to exercise JwkParser
        // (alg/kty/crv consistency, adcp_use, key_ops) with the request-signing taxonomy.
        String sigInputHeader = headers.get("signature-input");
        if (sigInputHeader == null) {
            sigInputHeader = headers.get("Signature-Input");
        }
        assertNotNull(sigInputHeader, "Missing Signature-Input header");

        Rfc9421Verifier.ParsedSignatureInput parsed;
        try {
            parsed = Rfc9421Verifier.parseSignatureInput(sigInputHeader);
        } catch (IllegalArgumentException e) {
            // Header malformed — this is a valid rejection path for 021/024.
            return new VerificationResult.Invalid("request_signature_header_malformed",
                    "Malformed Signature-Input: " + e.getMessage());
        }

        String kid = parsed.params().get("keyid");
        Map<String, Object> jwk = jwksByKeyId.get(kid);
        if (jwk == null) {
            return new VerificationResult.Invalid("request_signature_key_unknown",
                    "No key found for kid: " + kid);
        }

        VerificationKey key;
        try {
            key = JwkParser.parse(jwk, AdcpUse.REQUEST_SIGNING);
        } catch (VerificationException e) {
            // JWK validation failures (alg/kty/crv, adcp_use, key_ops) surface here.
            return new VerificationResult.Invalid(e.errorCode(), e.getMessage());
        }

        return Rfc9421Verifier.verify(signedInput, key, AdcpUse.REQUEST_SIGNING, referenceNow);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadJwks(JsonNode vector) throws IOException {
        if (vector.has("jwks_override")) {
            Map<String, Map<String, Object>> jwks = new LinkedHashMap<>();
            for (JsonNode keyNode : vector.get("jwks_override").get("keys")) {
                Map<String, Object> jwk = OM.convertValue(keyNode, Map.class);
                jwks.put((String) jwk.get("kid"), jwk);
            }
            return jwks;
        }
        if (vector.has("jwks_ref")) {
            JsonNode keysJson = loadResource("/compliance/request-signing/keys.json");
            Map<String, Map<String, Object>> jwks = new LinkedHashMap<>();
            List<String> refKids = new ArrayList<>();
            vector.get("jwks_ref").forEach(n -> refKids.add(n.asText()));
            for (JsonNode keyNode : keysJson.get("keys")) {
                String kid = keyNode.get("kid").asText();
                if (refKids.contains(kid)) {
                    Map<String, Object> jwk = OM.convertValue(keyNode, Map.class);
                    jwks.put(kid, jwk);
                }
            }
            return jwks;
        }
        return Map.of();
    }

    private JsonNode loadResource(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Test resource not found: " + path);
            }
            return OM.readTree(is);
        }
    }

    private static String sanitize(String name) {
        return name.replace(' ', '_').replace('/', '_').replace(':', '_');
    }
}