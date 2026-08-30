package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Computes and verifies {@code terms_digest} values per the AdCP 3.2
 * normative digest specification.
 *
 * <p>Format: {@code sha256:} + base64url(SHA-256(JCS(commercial_terms))),
 * where JCS is RFC 8785 JSON Canonicalization Scheme.
 *
 * <p>Buyer helpers should recompute and compare rather than trusting
 * the string. Alternative distinctness is defined as distinct
 * {@code commercial_terms}.
 */
public final class TermsDigest {

    private static final String PREFIX = "sha256:";

    private TermsDigest() {}

    /**
     * Computes the canonical digest for a commercial_terms JSON node.
     *
     * @return "sha256:" + base64url(SHA-256(JCS(commercialTerms)))
     */
    public static String compute(JsonNode commercialTerms) {
        byte[] canonical = canonicalize(commercialTerms);
        byte[] hash = sha256(canonical);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        return PREFIX + encoded;
    }

    /**
     * Verifies that a digest string matches the computed digest of
     * the given commercial terms.
     *
     * @return true if the digest is valid
     */
    public static boolean verify(@Nullable String digest, JsonNode commercialTerms) {
        if (digest == null || !digest.startsWith(PREFIX)) {
            return false;
        }
        String expected = compute(commercialTerms);
        return MessageDigest.isEqual(
                digest.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Checks whether two proposals have distinct commercial terms by
     * comparing their canonical digests.
     */
    public static boolean areDistinct(JsonNode termsA, JsonNode termsB) {
        return !compute(termsA).equals(compute(termsB));
    }

    /**
     * RFC 8785 JCS canonicalization delegated to the Java reference
     * implementation. Keeping number serialization in the reference library
     * avoids cross-language digest drift at IEEE-754 edge cases.
     */
    static byte[] canonicalize(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("commercial_terms must be an object");
        }
        try {
            return new JsonCanonicalizer(node.toString()).getEncodedUTF8();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to canonicalize JSON", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the JDK spec", e);
        }
    }
}
