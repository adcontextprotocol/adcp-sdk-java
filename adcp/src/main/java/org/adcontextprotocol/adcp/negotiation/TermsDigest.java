package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

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
     * RFC 8785 JCS canonicalization. Sorts object keys lexicographically
     * by UTF-16 code unit order, and formats numbers per ES2015 rules.
     *
     * <p>This is a self-contained implementation to avoid a runtime
     * dependency on org.webpki.jcs for the common case. The JCS number
     * formatting corner cases (very large/small doubles) follow the
     * ES2015 spec rather than Java's Double.toString().
     */
    static byte[] canonicalize(JsonNode node) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeCanonical(node, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to canonicalize JSON", e);
        }
    }

    private static void writeCanonical(JsonNode node, ByteArrayOutputStream out)
            throws IOException {
        switch (node.getNodeType()) {
            case OBJECT -> {
                ObjectNode obj = (ObjectNode) node;
                List<String> keys = new ArrayList<>();
                obj.fieldNames().forEachRemaining(keys::add);
                // JCS: sort by UTF-16 code unit order (String.compareTo)
                Collections.sort(keys);

                out.write('{');
                boolean first = true;
                for (String key : keys) {
                    if (!first) out.write(',');
                    first = false;
                    writeCanonicalString(key, out);
                    out.write(':');
                    writeCanonical(obj.get(key), out);
                }
                out.write('}');
            }
            case ARRAY -> {
                ArrayNode arr = (ArrayNode) node;
                out.write('[');
                boolean first = true;
                for (int i = 0; i < arr.size(); i++) {
                    if (!first) out.write(',');
                    first = false;
                    writeCanonical(arr.get(i), out);
                }
                out.write(']');
            }
            case STRING -> writeCanonicalString(node.textValue(), out);
            case NUMBER -> {
                // JCS number serialization per ES2015 (RFC 8785 §3.2.2.3)
                double d = node.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IOException("JCS does not support NaN or Infinity");
                }
                if (d == 0.0) {
                    // Normalize -0 to 0
                    out.write('0');
                } else {
                    long asLong = node.longValue();
                    if (d == (double) asLong && !node.isDouble()
                            && !node.isFloat()
                            && Math.abs(asLong) < (1L << 53)) {
                        out.write(Long.toString(asLong).getBytes(StandardCharsets.UTF_8));
                    } else {
                        // For decimal values, we use the representation that
                        // round-trips through parseDouble — per JCS spec.
                        String repr = jcsNumberString(d);
                        out.write(repr.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            case BOOLEAN -> out.write(
                    (node.booleanValue() ? "true" : "false")
                            .getBytes(StandardCharsets.UTF_8));
            case NULL -> out.write("null".getBytes(StandardCharsets.UTF_8));
            default -> throw new IOException("Unsupported JSON node type: " + node.getNodeType());
        }
    }

    private static void writeCanonicalString(String s, ByteArrayOutputStream out)
            throws IOException {
        out.write('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.write("\\\"".getBytes(StandardCharsets.UTF_8));
                case '\\' -> out.write("\\\\".getBytes(StandardCharsets.UTF_8));
                case '\b' -> out.write("\\b".getBytes(StandardCharsets.UTF_8));
                case '\f' -> out.write("\\f".getBytes(StandardCharsets.UTF_8));
                case '\n' -> out.write("\\n".getBytes(StandardCharsets.UTF_8));
                case '\r' -> out.write("\\r".getBytes(StandardCharsets.UTF_8));
                case '\t' -> out.write("\\t".getBytes(StandardCharsets.UTF_8));
                default -> {
                    if (c < 0x20) {
                        out.write(String.format("\\u%04x", (int) c)
                                .getBytes(StandardCharsets.UTF_8));
                    } else {
                        out.write(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }
        out.write('"');
    }

    /**
     * ES2015-compliant number-to-string conversion for JCS.
     * Produces the shortest decimal representation that round-trips
     * through parseDouble, per RFC 8785 section 3.2.2.3.
     */
    static String jcsNumberString(double d) {
        if (d == 0.0) return "0";
        if (d == (long) d && Math.abs(d) < 1e21) {
            return Long.toString((long) d);
        }
        String s = Double.toString(d);
        if (s.contains("E") || s.contains("e")) {
            // ES2015: lowercase 'e', no '+' sign, strip ".0" before 'e'
            // e.g. Java "1.0E-7" → JCS "1e-7"
            s = s.toLowerCase().replace("+", "");
            int eIdx = s.indexOf('e');
            String mantissa = s.substring(0, eIdx);
            String exponent = s.substring(eIdx);
            if (mantissa.endsWith(".0")) {
                mantissa = mantissa.substring(0, mantissa.length() - 2);
            }
            return mantissa + exponent;
        }
        return s;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the JDK spec", e);
        }
    }
}
