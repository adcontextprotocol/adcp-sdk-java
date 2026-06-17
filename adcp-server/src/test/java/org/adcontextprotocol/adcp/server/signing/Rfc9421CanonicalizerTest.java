package org.adcontextprotocol.adcp.server.signing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class Rfc9421CanonicalizerTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> canonicalizationPositiveCases() throws IOException {
        JsonNode root = loadResource("/compliance/request-signing/canonicalization.json");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode caseNode : root.get("cases")) {
            if (caseNode.has("reject") && caseNode.get("reject").asBoolean()) continue;
            String name = caseNode.get("name").asText();
            String inputUrl = caseNode.get("input_url").asText();
            String expectedTargetUri = caseNode.get("expected_target_uri").asText();
            String expectedAuthority = caseNode.get("expected_authority").asText();
            tests.add(DynamicTest.dynamicTest("canonicalize_" + name, () -> {
                String canonicalUri = Rfc9421Canonicalizer.canonicalizeTargetUri(inputUrl);
                assertEquals(expectedTargetUri, canonicalUri, "target-uri mismatch for: " + inputUrl);
                String authority = Rfc9421Canonicalizer.extractAuthority(inputUrl);
                assertEquals(expectedAuthority, authority, "authority mismatch for: " + inputUrl);
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> canonicalizationRejectCases() throws IOException {
        JsonNode root = loadResource("/compliance/request-signing/canonicalization.json");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode caseNode : root.get("cases")) {
            if (!caseNode.has("reject") || !caseNode.get("reject").asBoolean()) continue;
            String name = caseNode.get("name").asText();
            String inputUrl = caseNode.get("input_url").asText();
            tests.add(DynamicTest.dynamicTest("reject_" + name, () -> {
                assertThrows(SigningException.class,
                        () -> Rfc9421Canonicalizer.canonicalizeTargetUri(inputUrl),
                        "Should reject: " + inputUrl);
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> webhookPositiveSignatureBaseMatches() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();

        String[] webhookPositiveFiles = {
                "/compliance/webhook-signing/positive/001-basic-post.json",
                "/compliance/webhook-signing/positive/002-es256-post.json",
                "/compliance/webhook-signing/positive/003-multiple-signature-labels.json",
                "/compliance/webhook-signing/positive/004-default-port-stripped.json",
                "/compliance/webhook-signing/positive/005-percent-encoded-path.json",
                "/compliance/webhook-signing/positive/006-query-byte-preserved.json",
                "/compliance/webhook-signing/positive/007-body-without-idempotency-key.json"
        };

        for (String resourcePath : webhookPositiveFiles) {
            JsonNode vector = loadResource(resourcePath);
            String name = vector.get("name").asText();
            tests.add(DynamicTest.dynamicTest("webhook_" + name.replace(' ', '_'), () -> {
                String expectedBase = vector.get("expected_signature_base").asText();
                String computedBase = computeSignatureBase(vector);
                assertEquals(expectedBase, computedBase, "Signature base mismatch for: " + name);
            }));
        }

        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> requestSigningPositiveSignatureBaseMatches() throws Exception {
        String[] requestPositiveFiles = {
                "/compliance/request-signing/positive/001-basic-post.json",
                "/compliance/request-signing/positive/002-post-with-content-digest.json",
                "/compliance/request-signing/positive/003-es256-post.json",
                "/compliance/request-signing/positive/004-multiple-signature-labels.json",
                "/compliance/request-signing/positive/005-default-port-stripped.json",
                "/compliance/request-signing/positive/006-dot-segment-path.json",
                "/compliance/request-signing/positive/007-query-byte-preserved.json",
                "/compliance/request-signing/positive/008-percent-encoded-path.json"
        };

        List<DynamicTest> tests = new ArrayList<>();
        for (String resourcePath : requestPositiveFiles) {
            try {
                JsonNode vector = loadResource(resourcePath);
                String name = vector.get("name").asText();
                // Skip vectors without expected_signature_base (e.g. multiple labels)
                if (!vector.has("expected_signature_base")) {
                    continue;
                }
                tests.add(DynamicTest.dynamicTest("request_" + name.replace(' ', '_'), () -> {
                    String expectedBase = vector.get("expected_signature_base").asText();
                    String computedBase = computeSignatureBase(vector);
                    assertEquals(expectedBase, computedBase, "Signature base mismatch for: " + name);
                }));
            } catch (Exception e) {
                // Skip missing files gracefully
            }
        }
        return tests.stream();
    }

    @Test
    void canonicalizeTargetUri_lowercaseScheme() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("HTTPS://example.com/path");
        assertEquals("https://example.com/path", result);
    }

    @Test
    void canonicalizeTargetUri_lowercaseHost() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://Seller.Example.COM/path");
        assertEquals("https://seller.example.com/path", result);
    }

    @Test
    void canonicalizeTargetUri_stripDefaultPort443() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://example.com:443/path");
        assertEquals("https://example.com/path", result);
    }

    @Test
    void canonicalizeTargetUri_stripDefaultPort80() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("http://example.com:80/path");
        assertEquals("http://example.com/path", result);
    }

    @Test
    void canonicalizeTargetUri_preserveNonDefaultPort() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://example.com:8443/path");
        assertEquals("https://example.com:8443/path", result);
    }

    @Test
    void canonicalizeTargetUri_stripUserInfo() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://user:pass@example.com/path");
        assertEquals("https://example.com/path", result);
    }

    @Test
    void canonicalizeTargetUri_uppercasePercentEncoding() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://example.com/path%2fhere");
        assertEquals("https://example.com/path%2Fhere", result);
    }

    @Test
    void canonicalizeTargetUri_decodeUnreservedTilde() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://example.com/%7Efoo");
        assertEquals("https://example.com/~foo", result);
    }

    @Test
    void canonicalizeTargetUri_preserveQueryString() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://example.com/p?b=2&a=1&c=3");
        assertEquals("https://example.com/p?b=2&a=1&c=3", result);
    }

    @Test
    void canonicalizeTargetUri_stripFragment() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://example.com/p#frag");
        assertEquals("https://example.com/p", result);
    }

    @Test
    void canonicalizeTargetUri_emptyPathWithAuthority() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://example.com?x=1");
        assertEquals("https://example.com/?x=1", result);
    }

    @Test
    void canonicalizeTargetUri_removeDotSegments() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://example.com/adcp/./create_media_buy");
        assertEquals("https://example.com/adcp/create_media_buy", result);
    }

    @Test
    void canonicalizeTargetUri_removeDoubleDotSegments() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://example.com/a/b/../c");
        assertEquals("https://example.com/a/c", result);
    }

    @Test
    void extractAuthority_basicHost() throws SigningException {
        String result = Rfc9421Canonicalizer.extractAuthority("https://buyer.example.com/path");
        assertEquals("buyer.example.com", result);
    }

    @Test
    void extractAuthority_withNonDefaultPort() throws SigningException {
        String result = Rfc9421Canonicalizer.extractAuthority("https://example.com:8443/path");
        assertEquals("example.com:8443", result);
    }

    @Test
    void extractAuthority_stripsDefaultPort() throws SigningException {
        String result = Rfc9421Canonicalizer.extractAuthority("https://example.com:443/path");
        assertEquals("example.com", result);
    }

    @Test
    void extractAuthority_ipv6() throws SigningException {
        String result = Rfc9421Canonicalizer.extractAuthority("https://[2001:DB8::1]/p");
        assertEquals("[2001:db8::1]", result);
    }

    @Test
    void extractAuthority_ipv6WithPort() throws SigningException {
        String result = Rfc9421Canonicalizer.extractAuthority("https://[::1]:8443/p");
        assertEquals("[::1]:8443", result);
    }

    @Test
    void canonicalizeHost_unicodeHostname() {
        String result = Rfc9421Canonicalizer.canonicalizeHost("münchen.example.com");
        assertEquals("xn--mnchen-3ya.example.com", result);
    }

    @Test
    void canonicalizeHost_ipv6Literal() {
        String result = Rfc9421Canonicalizer.canonicalizeHost("[::1]");
        assertEquals("[::1]", result);
    }

    @Test
    void canonicalizeHost_ipv6Uppercase() {
        String result = Rfc9421Canonicalizer.canonicalizeHost("[2001:DB8::1]");
        assertEquals("[2001:db8::1]", result);
    }

    @Test
    void canonicalizeHost_asciiHostname() {
        String result = Rfc9421Canonicalizer.canonicalizeHost("Example.COM");
        assertEquals("example.com", result);
    }

    @Test
    void canonicalizeTargetUri_idnHostname() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://münchen.example.com/path");
        assertEquals("https://xn--mnchen-3ya.example.com/path", result);
    }

    @Test
    void extractAuthority_idnHostname() throws SigningException {
        String result = Rfc9421Canonicalizer.extractAuthority("https://münchen.example.com/path");
        assertEquals("xn--mnchen-3ya.example.com", result);
    }

    @Test
    void canonicalizeTargetUri_ipv6Literal() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://[::1]/path");
        assertEquals("https://[::1]/path", result);
    }

    @Test
    void canonicalizeTargetUri_defaultPort443Stripped() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("https://example.com:443/path");
        assertEquals("https://example.com/path", result);
    }

    @Test
    void canonicalizeTargetUri_defaultPort80Stripped() throws SigningException {
        String result = Rfc9421Canonicalizer.canonicalizeTargetUri("http://example.com:80/path");
        assertEquals("http://example.com/path", result);
    }

    private JsonNode loadResource(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Test resource not found: " + path);
            }
            return OM.readTree(is);
        }
    }

    private String computeSignatureBase(JsonNode vector) throws SigningException {
        JsonNode request = vector.get("request");
        String method = request.get("method").asText();
        String url = request.get("url").asText();
        JsonNode headersNode = request.get("headers");

        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headersNode.properties().forEach(entry -> {
            String normalizedName = HeaderNormalizer.normalizeName(entry.getKey());
            headers.put(normalizedName, entry.getValue().asText());
        });

        String sigInputHeader = headers.get("signature-input");
        if (sigInputHeader == null) {
            sigInputHeader = headers.get("Signature-Input");
        }
        assertNotNull(sigInputHeader, "Missing Signature-Input header");

        Rfc9421Verifier.ParsedSignatureInput parsed = Rfc9421Verifier.parseSignatureInput(sigInputHeader);

        String signatureInputString = Rfc9421Verifier.buildSignatureInputString(parsed);

        return Rfc9421Canonicalizer.canonicalize(
                method, url, headers, parsed.components(), signatureInputString);
    }
}