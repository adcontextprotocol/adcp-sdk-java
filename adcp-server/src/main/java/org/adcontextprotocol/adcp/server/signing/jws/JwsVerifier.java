package org.adcontextprotocol.adcp.server.signing.jws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * JWS (JSON Web Signature) verification for AdCP revocation lists.
 *
 * <p>Supports both compact serialization ({@code header.payload.signature})
 * and JSON general serialization. AdCP revocation lists use a narrow
 * allowed-alg set ({@code EdDSA} and {@code ES256}).
 *
 * <p>The verification process:
 * <ol>
 *   <li>Parse the JWS (compact or JSON general)</li>
 *   <li>Decode and validate the protected header</li>
 *   <li>Reject if {@code alg} is absent, is {@code "none"}, or not in the allowed set</li>
 *   <li>Optionally validate {@code typ} matches an expected value</li>
 *   <li>Resolve the key via the provided key resolver</li>
 *   <li>Verify the signature</li>
 *   <li>Decode the payload</li>
 * </ol>
 *
 * @see JwsVerificationResult
 * @see JwsDocument
 */
public final class JwsVerifier {

    private JwsVerifier() {}

    private static final ObjectMapper OM = new ObjectMapper();

    private static final Set<String> ALLOWED_ALGS = Set.of("EdDSA", "ES256");

    private static final Map<String, String> JWS_ALG_TO_JCA = Map.of(
            "EdDSA", "Ed25519",
            "ES256", "SHA256withECDSAinP1363Format"
    );

    /**
     * Verify a compact or JSON-general JWS document using the provided key.
     *
     * <p>The document can be a compact string ({@code header.payload.signature})
     * or a JSON object with {@code payload} and {@code signatures} fields.
     *
     * @param jwsDocument the JWS document as a string
     * @param key         the verification key
     * @return a {@link JwsVerificationResult}
     */
    public static JwsVerificationResult verify(String jwsDocument, VerificationKey key) {
        return verify(jwsDocument, key, null);
    }

    /**
     * Verify a compact or JSON-general JWS document with an optional expected
     * {@code typ} check.
     *
     * @param jwsDocument  the JWS document as a string
     * @param key          the verification key
     * @param expectedTyp  expected {@code typ} header value, or {@code null} to skip
     * @return a {@link JwsVerificationResult}
     */
    public static JwsVerificationResult verify(String jwsDocument, VerificationKey key,
            @Nullable String expectedTyp) {
        JwsDocument doc;
        try {
            doc = parse(jwsDocument);
        } catch (IllegalArgumentException e) {
            return new JwsVerificationResult.Invalid("Failed to parse JWS: " + e.getMessage());
        }
        return verifyDocument(doc, key, expectedTyp);
    }

    /**
     * Parse a JWS document (compact or JSON general) into its components.
     *
     * @param jwsDocument the JWS string
     * @return the parsed document
     * @throws IllegalArgumentException if parsing fails
     */
    public static JwsDocument parse(String jwsDocument) {
        String trimmed = jwsDocument.strip();
        if (trimmed.startsWith("{")) {
            return parseJsonGeneral(trimmed);
        }
        return parseCompact(trimmed);
    }

    /**
     * Verify a parsed JWS document.
     */
    public static JwsVerificationResult verifyDocument(JwsDocument doc, VerificationKey key,
            @Nullable String expectedTyp) {

        JsonNode header;
        try {
            byte[] headerBytes = Base64.getUrlDecoder().decode(doc.b64ProtectedHeader());
            header = OM.readTree(headerBytes);
        } catch (Exception e) {
            return new JwsVerificationResult.Invalid("Failed to decode JWS header: " + e.getMessage());
        }

        if (!header.isObject()) {
            return new JwsVerificationResult.Invalid("JWS header is not a JSON object");
        }

        JsonNode algNode = header.get("alg");
        if (algNode == null || !algNode.isTextual()) {
            return new JwsVerificationResult.Invalid("JWS header missing or invalid 'alg'");
        }
        String alg = algNode.asText();
        if ("none".equals(alg) || !ALLOWED_ALGS.contains(alg)) {
            return new JwsVerificationResult.Invalid("JWS alg '" + alg + "' not allowed");
        }

        if (expectedTyp != null) {
            JsonNode typNode = header.get("typ");
            if (typNode == null || !expectedTyp.equals(typNode.asText())) {
                String actual = typNode != null ? typNode.asText() : "(missing)";
                return new JwsVerificationResult.Invalid(
                        "JWS typ '" + actual + "' does not match expected '" + expectedTyp + "'");
            }
        }

        JsonNode critNode = header.get("crit");
        if (critNode != null && critNode.isArray() && !critNode.isEmpty()) {
            return new JwsVerificationResult.Invalid("JWS 'crit' header is not supported");
        }

        JsonNode kidNode = header.get("kid");
        if (kidNode == null || !kidNode.isTextual() || kidNode.asText().isEmpty()) {
            return new JwsVerificationResult.Invalid("JWS header must include a non-empty 'kid'");
        }
        String kid = kidNode.asText();

        String jcaAlg = JWS_ALG_TO_JCA.get(alg);
        if (jcaAlg == null) {
            return new JwsVerificationResult.Invalid("No JCA mapping for alg '" + alg + "'");
        }

        String signingInput = doc.b64ProtectedHeader() + "." + doc.b64Payload();
        try {
            PublicKey publicKey = key.asJcaKey();
            if (publicKey == null) {
                return new JwsVerificationResult.Invalid("Key resolution returned null for kid: " + kid);
            }
            Signature verifier = Signature.getInstance(jcaAlg);
            verifier.initVerify(publicKey);
            verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(doc.signature())) {
                return new JwsVerificationResult.Invalid("Signature verification failed for kid: " + kid);
            }
        } catch (Exception e) {
            return new JwsVerificationResult.Invalid("Signature verification error: " + e.getMessage());
        }

        String payload;
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(doc.b64Payload());
            payload = new String(payloadBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new JwsVerificationResult.Invalid("Failed to decode JWS payload: " + e.getMessage());
        }

        return new JwsVerificationResult.Valid(payload, kid);
    }

    private static JwsDocument parseCompact(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Compact JWS must have exactly 3 dot-separated segments, got " + parts.length);
        }
        if (parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("Compact JWS has an empty segment");
        }
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (Exception e) {
            throw new IllegalArgumentException("Compact JWS signature is not valid base64url: " + e.getMessage());
        }
        return new JwsDocument(parts[0], parts[1], signature);
    }

    private static JwsDocument parseJsonGeneral(String json) {
        JsonNode doc;
        try {
            doc = OM.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("JWS JSON document is not valid JSON: " + e.getMessage());
        }
        if (!doc.isObject()) {
            throw new IllegalArgumentException("JWS general JSON document must be an object");
        }
        JsonNode payloadNode = doc.get("payload");
        if (payloadNode == null || !payloadNode.isTextual()) {
            throw new IllegalArgumentException("JWS general JSON document must have 'payload' string");
        }
        JsonNode sigsNode = doc.get("signatures");
        if (sigsNode == null || !sigsNode.isArray() || sigsNode.isEmpty()) {
            throw new IllegalArgumentException("JWS general JSON 'signatures' must be a non-empty array");
        }
        if (sigsNode.size() > 1) {
            throw new IllegalArgumentException(
                    "JWS general JSON 'signatures' with multiple entries is not supported");
        }
        JsonNode entry = sigsNode.get(0);
        JsonNode protectedNode = entry.get("protected");
        JsonNode sigNode = entry.get("signature");
        if (protectedNode == null || !protectedNode.isTextual() || sigNode == null || !sigNode.isTextual()) {
            throw new IllegalArgumentException("JWS signature entry missing 'protected' or 'signature'");
        }
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(sigNode.asText());
        } catch (Exception e) {
            throw new IllegalArgumentException("JWS signature is not valid base64url: " + e.getMessage());
        }
        return new JwsDocument(protectedNode.asText(), payloadNode.asText(), signature);
    }
}