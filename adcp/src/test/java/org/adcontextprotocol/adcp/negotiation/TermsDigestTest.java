package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TermsDigestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compute_returns_sha256_prefixed_digest() {
        ObjectNode terms = mapper.createObjectNode();
        terms.put("total_budget", 50000);
        terms.put("currency", "USD");

        String digest = TermsDigest.compute(terms);

        assertTrue(digest.startsWith("sha256:"));
        // base64url: no padding, no +, no /
        String encoded = digest.substring(7);
        assertFalse(encoded.contains("="));
        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("/"));
    }

    @Test
    void compute_is_deterministic() {
        ObjectNode terms = mapper.createObjectNode();
        terms.put("currency", "USD");
        terms.put("total_budget", 50000);

        String digest1 = TermsDigest.compute(terms);
        String digest2 = TermsDigest.compute(terms);

        assertEquals(digest1, digest2);
    }

    @Test
    void compute_is_key_order_independent() {
        ObjectNode terms1 = mapper.createObjectNode();
        terms1.put("currency", "USD");
        terms1.put("total_budget", 50000);

        ObjectNode terms2 = mapper.createObjectNode();
        terms2.put("total_budget", 50000);
        terms2.put("currency", "USD");

        assertEquals(TermsDigest.compute(terms1), TermsDigest.compute(terms2));
    }

    @Test
    void verify_succeeds_for_matching_digest() {
        ObjectNode terms = mapper.createObjectNode();
        terms.put("price", 10.5);

        String digest = TermsDigest.compute(terms);

        assertTrue(TermsDigest.verify(digest, terms));
    }

    @Test
    void verify_fails_for_tampered_terms() {
        ObjectNode original = mapper.createObjectNode();
        original.put("price", 10.5);

        String digest = TermsDigest.compute(original);

        ObjectNode tampered = mapper.createObjectNode();
        tampered.put("price", 15.0);

        assertFalse(TermsDigest.verify(digest, tampered));
    }

    @Test
    void verify_fails_for_null_digest() {
        ObjectNode terms = mapper.createObjectNode();
        assertFalse(TermsDigest.verify(null, terms));
    }

    @Test
    void verify_fails_for_wrong_prefix() {
        ObjectNode terms = mapper.createObjectNode();
        assertFalse(TermsDigest.verify("md5:abc", terms));
    }

    @Test
    void areDistinct_detects_different_terms() {
        ObjectNode terms1 = mapper.createObjectNode();
        terms1.put("price", 10);

        ObjectNode terms2 = mapper.createObjectNode();
        terms2.put("price", 20);

        assertTrue(TermsDigest.areDistinct(terms1, terms2));
    }

    @Test
    void areDistinct_detects_identical_terms() {
        ObjectNode terms1 = mapper.createObjectNode();
        terms1.put("price", 10);

        ObjectNode terms2 = mapper.createObjectNode();
        terms2.put("price", 10);

        assertFalse(TermsDigest.areDistinct(terms1, terms2));
    }

    @Test
    void jcs_canonicalization_handles_nested_objects() {
        ObjectNode inner = mapper.createObjectNode();
        inner.put("b", 2);
        inner.put("a", 1);

        ObjectNode outer = mapper.createObjectNode();
        outer.put("z", "last");
        outer.set("nested", inner);

        byte[] canonical = TermsDigest.canonicalize(outer);
        String result = new String(canonical, StandardCharsets.UTF_8);

        // Keys should be sorted: nested before z, and within nested: a before b
        assertTrue(result.indexOf("\"nested\"") < result.indexOf("\"z\""));
        assertTrue(result.indexOf("\"a\"") < result.indexOf("\"b\""));
    }

    @Test
    void jcs_number_string_handles_integers() {
        assertEquals("0", TermsDigest.jcsNumberString(0.0));
        assertEquals("42", TermsDigest.jcsNumberString(42.0));
        assertEquals("-17", TermsDigest.jcsNumberString(-17.0));
    }

    @Test
    void jcs_number_string_handles_decimals() {
        String result = TermsDigest.jcsNumberString(3.14);
        assertEquals("3.14", result);
    }

    @Test
    void jcs_number_string_strips_dot_zero_in_exponent() {
        // Java Double.toString(1e-7) → "1.0E-7"; JCS requires "1e-7"
        assertEquals("1e-7", TermsDigest.jcsNumberString(1e-7));
        assertEquals("1e-20", TermsDigest.jcsNumberString(1e-20));
    }

    @Test
    void jcs_number_string_keeps_fractional_exponent() {
        // 1.5e10 = 15000000000 → integer path
        assertEquals("15000000000", TermsDigest.jcsNumberString(1.5e10));
        // 1.5e21 stays exponential since > 1e21
        assertEquals("1.5e21", TermsDigest.jcsNumberString(1.5e21));
    }

    @Test
    void jcs_handles_string_escaping() {
        ObjectNode node = mapper.createObjectNode();
        node.put("msg", "hello\nworld\t\"quoted\"");

        byte[] canonical = TermsDigest.canonicalize(node);
        String result = new String(canonical, StandardCharsets.UTF_8);

        assertTrue(result.contains("\\n"));
        assertTrue(result.contains("\\t"));
        assertTrue(result.contains("\\\""));
    }

    @Test
    void jcs_handles_boolean_and_null() {
        ObjectNode node = mapper.createObjectNode();
        node.put("flag", true);
        node.putNull("empty");

        byte[] canonical = TermsDigest.canonicalize(node);
        String result = new String(canonical, StandardCharsets.UTF_8);

        assertTrue(result.contains("true"));
        assertTrue(result.contains("null"));
    }
}
