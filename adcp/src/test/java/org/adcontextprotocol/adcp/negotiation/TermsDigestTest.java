package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TermsDigestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void digest_is_base64url_deterministic_and_key_order_independent() {
        ObjectNode a = mapper.createObjectNode().put("currency", "USD").put("amount", 50000);
        ObjectNode b = mapper.createObjectNode().put("amount", 50000).put("currency", "USD");
        String digest = TermsDigest.compute(a);
        assertEquals(digest, TermsDigest.compute(b));
        assertTrue(digest.matches("sha256:[A-Za-z0-9_-]{43}"));
        assertTrue(TermsDigest.verify(digest, b));
    }

    @Test
    void digest_rejects_tampering_and_bad_prefix() {
        ObjectNode original = mapper.createObjectNode().put("price", 10.5);
        ObjectNode changed = mapper.createObjectNode().put("price", 15.0);
        assertFalse(TermsDigest.verify(TermsDigest.compute(original), changed));
        assertFalse(TermsDigest.verify("md5:abc", original));
        assertFalse(TermsDigest.verify(null, original));
    }

    @Test
    void reference_jcs_handles_utf16_key_order_escaping_and_ieee754_numbers() throws Exception {
        var input = mapper.readTree("""
                {"z":"hello\\nworld","a":{"small":1e-7,"large":1.5e21,"whole":1.5e10}}
                """);
        String canonical = new String(TermsDigest.canonicalize(input), StandardCharsets.UTF_8);
        assertEquals("{\"a\":{\"large\":1.5e+21,\"small\":1e-7,\"whole\":15000000000},"
                + "\"z\":\"hello\\nworld\"}", canonical);
    }

    @Test
    void distinctness_compares_canonical_commercial_terms() {
        assertTrue(TermsDigest.areDistinct(
                mapper.createObjectNode().put("price", 10),
                mapper.createObjectNode().put("price", 20)));
        assertFalse(TermsDigest.areDistinct(
                mapper.createObjectNode().put("price", 10),
                mapper.createObjectNode().put("price", 10)));
    }
}
