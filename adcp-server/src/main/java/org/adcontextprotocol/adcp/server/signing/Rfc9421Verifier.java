package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SignedInput;
import org.adcontextprotocol.adcp.signing.VerificationException;
import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC 9421 verification implementation. Takes a {@link SignedInput} (raw bytes
 * and headers from the wire) + a {@link VerificationKey} and verifies the signature.
 *
 * <p>Produces a {@link VerificationResult} (sealed interface: {@code Valid} or
 * {@code Invalid} with error code).
 */
public final class Rfc9421Verifier {

    private Rfc9421Verifier() {}

    private static final Pattern SIGNATURE_INPUT_PATTERN = Pattern.compile(
            "(\\w+)=\\(([^)]*)\\)((?:;[^=]+=\\([^)]*\\)|;\\w+=\"[^\"]*\"|;\\w+=\\d+)+)"
    );

    private static final String WEBCON_TAG = "adcp/webhook-signing/v1";
    private static final String REQ_TAG = "adcp/request-signing/v1";

    /**
     * Verify an inbound webhook signature.
     *
     * @param input     the signed input from the wire
     * @param key       the verification key
     * @param purpose   the expected AdCP use (must be WEBHOOK_SIGNING or REQUEST_SIGNING)
     * @param referenceNow Unix seconds representing "now" for window validation
     * @return a VerificationResult
     */
    public static VerificationResult verify(
            SignedInput input,
            VerificationKey key,
            AdcpUse purpose,
            long referenceNow) {

        String prefix = purpose == AdcpUse.WEBHOOK_SIGNING ? "webhook_signature_" : "request_signature_";

        // Step 1: Parse Signature-Input header
        String sigInputHeader = firstHeader(input.headers(), "Signature-Input");
        if (sigInputHeader == null) {
            return new VerificationResult.Invalid(prefix + "header_malformed",
                    "Missing Signature-Input header");
        }

        String sigHeader = firstHeader(input.headers(), "Signature");
        if (sigHeader == null) {
            return new VerificationResult.Invalid(prefix + "header_malformed",
                    "Missing Signature header");
        }

        // Parse the label — we look for "sig1" per AdCP convention
        ParsedSignatureInput parsed;
        try {
            parsed = parseSignatureInput(sigInputHeader);
        } catch (IllegalArgumentException e) {
            return new VerificationResult.Invalid(prefix + "header_malformed",
                    "Malformed Signature-Input: " + e.getMessage());
        }

        // Step 2: Check required signature parameters
        for (String required : AdcpSignatureProfile.REQUIRED_SIGNATURE_PARAMS) {
            if (!parsed.params().containsKey(required)) {
                return new VerificationResult.Invalid(prefix + "params_incomplete",
                        "Missing required signature parameter: " + required);
            }
        }

        // Step 3: Check tag
        String tag = parsed.params().get("tag");
        String expectedTag = AdcpSignatureProfile.tagForUse(purpose);
        if (tag == null || !expectedTag.equals(tag)) {
            return new VerificationResult.Invalid(prefix + "tag_invalid",
                    "Expected tag=" + expectedTag + ", got: " + tag);
        }

        // Step 4: Check alg
        String alg = parsed.params().get("alg");
        if (alg == null || !AdcpSignatureProfile.isAlgorithmAllowed(alg)) {
            return new VerificationResult.Invalid(prefix + "alg_not_allowed",
                    "Algorithm not allowed: " + alg);
        }

        // Step 5: Check signature window
        long created = Long.parseLong(parsed.params().getOrDefault("created", "0"));
        long expires = Long.parseLong(parsed.params().getOrDefault("expires", "0"));
        if (expires <= created) {
            return new VerificationResult.Invalid(prefix + "window_invalid",
                    "expires <= created");
        }
        if (referenceNow > expires || referenceNow < created) {
            return new VerificationResult.Invalid(prefix + "window_invalid",
                    "Signature window expired or not yet valid");
        }
        if (expires - created > AdcpSignatureProfile.REPLAY_WINDOW_SECONDS) {
            return new VerificationResult.Invalid(prefix + "window_invalid",
                    "Signature window exceeds " + AdcpSignatureProfile.REPLAY_WINDOW_SECONDS + "s");
        }

        // Step 6: Check required covered components
        String componentsError = AdcpSignatureProfile.validateRequiredComponents(purpose, parsed.components());
        if (componentsError != null) {
            return new VerificationResult.Invalid(componentsError,
                    "Missing required covered component(s)");
        }

        // Step 7: Key lookup — handled by caller, key is provided

        // Step 8: Key purpose check — handled by caller via VerificationKeyLookup

        // Steps 9-9a: Revocation and rate checks — stateful, handled by caller

        // Step 10: Verify signature
        // Reconstruct the signature base
        String signatureBase;
        try {
            String signatureInputForBase = buildSignatureInputString(parsed);
            Map<String, String> headers = input.headers();

            // Content-Digest verification (step 11)
            String contentDigestHeader = firstHeader(headers, "Content-Digest");
            if (contentDigestHeader != null && input.rawBody() != null && input.rawBody().length > 0) {
                String expectedDigest = ContentDigest.sha256(input.rawBody());
                if (!contentDigestHeader.equals(expectedDigest)) {
                    return new VerificationResult.Invalid(prefix + "digest_mismatch",
                            "Content-Digest header does not match body");
                }
            }

            signatureBase = Rfc9421Canonicalizer.canonicalize(
                    input.method(),
                    input.targetUri(),
                    headers,
                    parsed.components(),
                    signatureInputForBase);
        } catch (Exception e) {
            return new VerificationResult.Invalid(prefix + "header_malformed",
                    "Failed to build signature base: " + e.getMessage());
        }

        // Extract the signature bytes from the Signature header
        byte[] signatureBytes = extractSignatureBytes(sigHeader, parsed.label());
        if (signatureBytes == null) {
            return new VerificationResult.Invalid(prefix + "header_malformed",
                    "Could not extract signature bytes");
        }

        // Verify using the public key
        try {
            PublicKey publicKey = key.asJcaKey();
            String jcaAlg;
            if (AdcpSignatureProfile.ALG_ED25519.equals(alg)) {
                jcaAlg = "Ed25519";
            } else if (AdcpSignatureProfile.ALG_ECDSA_P256_SHA256.equals(alg)) {
                jcaAlg = "SHA256withECDSAinP1363Format";
            } else {
                return new VerificationResult.Invalid(prefix + "alg_not_allowed",
                        "Unsupported algorithm: " + alg);
            }

            Signature verifier = Signature.getInstance(jcaAlg);
            verifier.initVerify(publicKey);
            verifier.update(signatureBase.getBytes(StandardCharsets.UTF_8));
            boolean valid = verifier.verify(signatureBytes);
            if (!valid) {
                return new VerificationResult.Invalid(prefix + "invalid",
                        "Signature verification failed");
            }
        } catch (Exception e) {
            return new VerificationResult.Invalid(prefix + "invalid",
                    "Signature verification error: " + e.getMessage());
        }

        return new VerificationResult.Valid(key.kid());
    }

    /**
     * Reconstruct the Signature-Input string from parsed components for the signature base.
     */
    static String buildSignatureInputString(ParsedSignatureInput parsed) {
        StringBuilder sb = new StringBuilder();
        sb.append(parsed.label()).append("=(");
        List<String> components = parsed.components();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append('"').append(components.get(i)).append('"');
        }
        sb.append(')');
        sb.append(";created=").append(parsed.params().get("created"));
        sb.append(";expires=").append(parsed.params().get("expires"));
        sb.append(";nonce=\"").append(parsed.params().get("nonce")).append('"');
        sb.append(";keyid=\"").append(parsed.params().get("keyid")).append('"');
        sb.append(";alg=\"").append(parsed.params().get("alg")).append('"');
        sb.append(";tag=\"").append(parsed.params().get("tag")).append('"');
        return sb.toString();
    }

    private static @Nullable String firstHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Parse the Signature-Input header value.
     *
     * <p>Example: {@code sig1=("@method" "@target-uri" "@authority" "content-type" "content-digest");created=1776520800;expires=1776521100;nonce="KXYnfEfJ0PBRZXQyVXfVQA";keyid="test-ed25519-webhook-2026";alg="ed25519";tag="adcp/webhook-signing/v1"}
     */
    static ParsedSignatureInput parseSignatureInput(String header) {
        // Find the sig1 label specifically (AdCP convention)
        // In case of multiple labels: "sig1=(...);..., relay=(...);..."
        // We need to extract just the sig1 portion.
        String sig1Portion = extractLabelPortion(header, "sig1");
        if (sig1Portion == null) {
            // Fall back to first label
            sig1Portion = header;
        }

        int eqIdx = sig1Portion.indexOf('=');
        if (eqIdx == -1) {
            throw new IllegalArgumentException("Missing '=' in Signature-Input");
        }
        String label = sig1Portion.substring(0, eqIdx).trim();

        // Find the component list between parens
        int openParen = sig1Portion.indexOf('(', eqIdx);
        int closeParen = sig1Portion.indexOf(')', openParen);
        if (openParen == -1 || closeParen == -1) {
            throw new IllegalArgumentException("Missing component list in Signature-Input");
        }

        String componentList = sig1Portion.substring(openParen + 1, closeParen).trim();
        List<String> components = new ArrayList<>();
        for (String component : componentList.split("\\s+")) {
            String trimmed = component.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                }
                components.add(trimmed);
            }
        }

        String paramsStr = sig1Portion.substring(closeParen + 1);
        Map<String, String> params = new java.util.LinkedHashMap<>();
        parseParams(paramsStr, params);

        return new ParsedSignatureInput(label, components, params);
    }

    /**
     * Extract a specific label's portion from a multi-label Signature-Input header.
     * E.g., from "sig1=(...);..., relay=(...);..." extract "sig1=(...);..."
     */
    static @Nullable String extractLabelPortion(String header, String targetLabel) {
        // Find the target label
        int start = header.indexOf(targetLabel + "=");
        if (start == -1) return null;

        // Find the end: either a comma followed by another label, or end of string
        // The boundary is ", labelName=(" — but we need to skip past quoted strings
        // and parenthesized groups.
        int pos = start;
        int depth = 0;
        boolean inQuote = false;
        while (pos < header.length()) {
            char c = header.charAt(pos);
            if (inQuote) {
                if (c == '\\' && pos + 1 < header.length()) {
                    pos += 2;
                    continue;
                }
                if (c == '"') {
                    inQuote = false;
                }
                pos++;
                continue;
            }
            if (c == '"') {
                inQuote = true;
                pos++;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                // Check if the next non-space is a label (word followed by =)
                int nextPos = pos + 1;
                while (nextPos < header.length() && header.charAt(nextPos) == ' ') nextPos++;
                // This comma separates labels
                return header.substring(start, pos);
            }
            pos++;
        }
        return header.substring(start);
    }

    private static void parseParams(String paramsStr, Map<String, String> params) {
        int i = 0;
        while (i < paramsStr.length()) {
            if (paramsStr.charAt(i) == ';') {
                i++;
            }
            // Skip whitespace
            while (i < paramsStr.length() && paramsStr.charAt(i) == ' ') i++;
            if (i >= paramsStr.length()) break;

            // Read param name
            int nameStart = i;
            while (i < paramsStr.length() && paramsStr.charAt(i) != '=' && paramsStr.charAt(i) != ';') i++;
            String name = paramsStr.substring(nameStart, i).trim();
            if (name.isEmpty()) continue;

            if (i < paramsStr.length() && paramsStr.charAt(i) == '=') {
                i++;
                if (i < paramsStr.length() && paramsStr.charAt(i) == '"') {
                    // Quoted string
                    i++; // skip opening quote
                    int valueStart = i;
                    while (i < paramsStr.length() && paramsStr.charAt(i) != '"') i++;
                    String value = paramsStr.substring(valueStart, i);
                    params.put(name, value);
                    i++; // skip closing quote
                } else {
                    // Unquoted value (bare integer)
                    int valueStart = i;
                    while (i < paramsStr.length() && paramsStr.charAt(i) != ';') i++;
                    String value = paramsStr.substring(valueStart, i).trim();
                    params.put(name, value);
                }
            }
        }
    }

    private static @Nullable byte[] extractSignatureBytes(String sigHeader, String label) {
        // Format: sig1=:base64url-no-padding:
        String prefix = label + "=:";
        int start = sigHeader.indexOf(prefix);
        if (start == -1) return null;
        start += prefix.length();
        int end = sigHeader.indexOf(':', start);
        if (end == -1) return null;
        String b64 = sigHeader.substring(start, end);
        return Base64.getUrlDecoder().decode(b64);
    }

    /**
     * Parsed Signature-Input components.
     */
    record ParsedSignatureInput(
            String label,
            List<String> components,
            Map<String, String> params
    ) {}
}