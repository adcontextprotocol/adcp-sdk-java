package org.adcontextprotocol.adcp.server.signing.jwks;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.VerificationException;
import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.jspecify.annotations.Nullable;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parses JWK JSON objects into {@link VerificationKey} instances.
 *
 * <p>Supports OKP (Ed25519), EC (P-256, P-384), and RSA key types.
 * Validates {@code adcp_use} matches the expected {@link AdcpUse} from
 * the verification input, and validates that {@code key_ops} includes
 * {@code "verify"} (per test vector 020).
 */
public final class JwkParser {

    private JwkParser() {}

    /**
     * Parse a single JWK JSON object into a {@link VerificationKey}.
     *
     * @param jwk         the JWK as a string-keyed map
     * @param expectedUse the expected AdCP use, or {@code null} to skip validation
     * @return the parsed verification key
     * @throws VerificationException if the JWK is invalid or fails validation
     */
    public static VerificationKey parse(Map<String, Object> jwk, @Nullable AdcpUse expectedUse)
            throws VerificationException {
        Objects.requireNonNull(jwk, "jwk");

        String kid = requireString(jwk, "kid", expectedUse);
        String kty = requireString(jwk, "kty", expectedUse);

        validateAdcpUse(jwk, expectedUse);
        validateKeyOps(jwk, expectedUse);

        // Validate the JWK's declared alg is consistent with kty/crv BEFORE
        // attempting to parse the key material. RFC 8037 binds alg=EdDSA to OKP
        // keys (Ed25519/Ed448); alg=ES256/ES384 to EC keys with P-256/P-384
        // respectively. A JWK declaring an impossible alg/kty/crv combination
        // is malformed and MUST be rejected at key-purpose validation (verifier
        // checklist step 8), not at crypto verify (step 10). Validating before
        // parsing ensures a malformed alg/kty/crv (e.g. alg=EdDSA with kty=EC
        // crv=P-256) fails with key_purpose_invalid rather than a later crypto
        // error from incompatible key material.
        String crv = jwk.get("crv") instanceof String s ? s : null;
        validateAlgConsistency(jwk, kty, crv, expectedUse);

        return switch (kty) {
            case "OKP" -> parseOkp(jwk, kid, expectedUse);
            case "EC" -> parseEc(jwk, kid, expectedUse);
            case "RSA" -> parseRsa(jwk, kid, expectedUse);
            default -> throw new VerificationException(
                    errorCode(expectedUse, "invalid"),
                    "Unsupported JWK kty: " + kty);
        };
    }

    /**
     * Build the error code with the correct taxonomy prefix for the expected use.
     * Webhook signing uses {@code webhook_signature_*}; request signing uses
     * {@code request_signature_*}.
     */
    private static String errorCode(@Nullable AdcpUse expectedUse, String suffix) {
        String prefix = (expectedUse == AdcpUse.REQUEST_SIGNING)
                ? "request_signature_"
                : "webhook_signature_";
        return prefix + suffix;
    }

    private static VerificationKey parseOkp(Map<String, Object> jwk, String kid, @Nullable AdcpUse expectedUse)
            throws VerificationException {
        String crv = requireString(jwk, "crv", expectedUse);
        if (!"Ed25519".equals(crv)) {
            throw new VerificationException(
                    errorCode(expectedUse, "invalid"),
                    "Unsupported OKP curve: " + crv);
        }
        String xB64 = requireString(jwk, "x", expectedUse);
        byte[] x = base64urlDecode(xB64, expectedUse);
        if (x.length != 32) {
            throw new VerificationException(
                    errorCode(expectedUse, "invalid"),
                    "Ed25519 public key must be 32 bytes, got " + x.length);
        }

        byte[] der = encodeEd25519Der(x);
        return new VerificationKey(kid, "Ed25519", der, "Ed25519");
    }

    private static VerificationKey parseEc(Map<String, Object> jwk, String kid, @Nullable AdcpUse expectedUse)
            throws VerificationException {
        String crv = requireString(jwk, "crv", expectedUse);
        String xB64 = requireString(jwk, "x", expectedUse);
        String yB64 = requireString(jwk, "y", expectedUse);

        String algorithm;
        int fieldSizeBits;

        switch (crv) {
            case "P-256" -> {
                algorithm = "EC";
                fieldSizeBits = 256;
            }
            case "P-384" -> {
                algorithm = "EC";
                fieldSizeBits = 384;
            }
            default -> throw new VerificationException(
                    errorCode(expectedUse, "invalid"),
                    "Unsupported EC curve: " + crv);
        }

        byte[] xBytes = base64urlDecode(xB64, expectedUse);
        byte[] yBytes = base64urlDecode(yB64, expectedUse);

        int expectedOctets = fieldSizeBits / 8;
        xBytes = padToLength(xBytes, expectedOctets);
        yBytes = padToLength(yBytes, expectedOctets);

        byte[] der = encodeEcDer(crv, xBytes, yBytes, expectedUse);
        return new VerificationKey(kid, algorithm, der, crv);
    }

    private static VerificationKey parseRsa(Map<String, Object> jwk, String kid, @Nullable AdcpUse expectedUse)
            throws VerificationException {
        String nB64 = requireString(jwk, "n", expectedUse);
        String eB64 = requireString(jwk, "e", expectedUse);

        byte[] n = base64urlDecode(nB64, expectedUse);
        byte[] e = base64urlDecode(eB64, expectedUse);

        byte[] der = encodeRsaDer(n, e);
        return new VerificationKey(kid, "RSA", der, null);
    }

    /**
     * Validate that the JWK's declared {@code alg} (if present) is consistent
     * with its {@code kty} and {@code crv}. RFC 8037 binds alg values to specific
     * key types and curves; an impossible combination (e.g. alg=EdDSA with
     * kty=EC crv=P-256) means the key is malformed and MUST be rejected at
     * key-purpose validation, not at crypto verify.
     */
    private static void validateAlgConsistency(
            Map<String, Object> jwk, String kty, @Nullable String crv, @Nullable AdcpUse expectedUse)
            throws VerificationException {
        Object algObj = jwk.get("alg");
        if (algObj == null) {
            return;
        }
        String alg = algObj.toString();

        boolean consistent = switch (kty) {
            case "OKP" -> "EdDSA".equals(alg);
            case "EC" -> switch (crv) {
                case "P-256" -> "ES256".equals(alg);
                case "P-384" -> "ES384".equals(alg);
                // Unknown/null crv: cannot confirm consistency, so reject.
                default -> false;
            };
            case "RSA" -> alg.startsWith("RS") || alg.startsWith("PS");
            default -> true;
        };

        if (!consistent) {
            throw new VerificationException(
                    errorCode(expectedUse, "key_purpose_invalid"),
                    "JWK alg=" + alg + " is inconsistent with kty=" + kty
                            + (crv != null ? " crv=" + crv : "")
                            + " per RFC 8037/JWK alg bindings");
        }
    }

    private static void validateAdcpUse(Map<String, Object> jwk, @Nullable AdcpUse expectedUse)
            throws VerificationException {
        if (expectedUse == null) {
            return;
        }
        Object adcpUseObj = jwk.get("adcp_use");
        if (adcpUseObj == null) {
            throw new VerificationException(
                    errorCode(expectedUse, "key_purpose_invalid"),
                    "JWK missing required 'adcp_use' parameter");
        }
        String adcpUseStr = adcpUseObj.toString();
        AdcpUse jwkUse;
        try {
            jwkUse = AdcpUse.fromWireName(adcpUseStr);
        } catch (IllegalArgumentException e) {
            throw new VerificationException(
                    errorCode(expectedUse, "key_purpose_invalid"),
                    "Unknown adcp_use value: " + adcpUseStr);
        }
        if (jwkUse != expectedUse) {
            throw new VerificationException(
                    errorCode(expectedUse, "key_purpose_invalid"),
                    "JWK adcp_use=" + adcpUseStr + " does not match expected "
                            + expectedUse.wireName());
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateKeyOps(Map<String, Object> jwk, @Nullable AdcpUse expectedUse)
            throws VerificationException {
        Object keyOpsObj = jwk.get("key_ops");
        if (keyOpsObj == null) {
            return;
        }
        if (!(keyOpsObj instanceof List<?> ops)) {
            throw new VerificationException(
                    errorCode(expectedUse, "invalid"),
                    "JWK key_ops must be an array");
        }
        boolean hasVerify = false;
        for (Object op : ops) {
            if ("verify".equals(op)) {
                hasVerify = true;
                break;
            }
        }
        if (!hasVerify) {
            throw new VerificationException(
                    errorCode(expectedUse, "key_purpose_invalid"),
                    "JWK key_ops does not include 'verify'");
        }
    }

    // -- Base64url decoding --

    private static byte[] base64urlDecode(String b64, @Nullable AdcpUse expectedUse) throws VerificationException {
        try {
            String padded = b64;
            int padNeeded = (4 - padded.length() % 4) % 4;
            padded += "=".repeat(padNeeded);
            return Base64.getUrlDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            throw new VerificationException(
                    errorCode(expectedUse, "invalid"),
                    "Invalid base64url encoding", e);
        }
    }

    // -- DER encoding helpers --

    private static byte[] encodeEd25519Der(byte[] rawKey) throws VerificationException {
        byte[] algorithmIdentifier = hexToBytes("30213006062B6570050100");
        byte[] bitString = wrapBitString(rawKey);
        return concat(algorithmIdentifier, bitString);
    }

    private static byte[] encodeEcDer(String crv, byte[] x, byte[] y, @Nullable AdcpUse expectedUse)
            throws VerificationException {
        String oid;
        if ("P-256".equals(crv)) {
            oid = "06082A8648CE3D030107";
        } else if ("P-384".equals(crv)) {
            oid = "06052B81040022";
        } else {
            throw new VerificationException(
                    errorCode(expectedUse, "invalid"),
                    "Unsupported EC curve for DER encoding: " + crv);
        }

        byte[] ecPoint = concat(new byte[]{0x04}, x, y);
        byte[] algId = concat(hexToBytes("3013"), hexToBytes(oid.length() / 2 == 8
                ? "300606" + oid.substring(0, 2) + "0" + oid.substring(3)
                : "3007" + oid), new byte[]{0x05, 0x00});
        // Re-encode algorithm identifier properly
        byte[] curveOidBytes = hexToBytes(oid);
        if ("P-256".equals(crv)) {
            algId = concat(hexToBytes("301306072A8648CE3D020106082A8648CE3D030107"));
        } else {
            algId = concat(hexToBytes("301006072A8648CE3D020106052B81040022"));
        }

        byte[] bitString = wrapBitString(ecPoint);
        return concat(algId, bitString);
    }

    private static byte[] encodeRsaDer(byte[] n, byte[] e) throws VerificationException {
        byte[] nEncoded = derEncodeInteger(n);
        byte[] eEncoded = derEncodeInteger(e);
        byte[] rsaPublicKey = derEncodeSequence(nEncoded, eEncoded);
        byte[] algorithmIdentifier = hexToBytes("300D06092A864886F70D0101010500");
        byte[] bitString = wrapBitString(rsaPublicKey);
        return concat(algorithmIdentifier, bitString);
    }

    private static byte[] wrapBitString(byte[] content) {
        byte[] bs = new byte[content.length + 1];
        bs[0] = 0x00;
        System.arraycopy(content, 0, bs, 1, content.length);
        return derEncode(0x03, bs);
    }

    private static byte[] derEncodeInteger(byte[] value) {
        if (value.length > 0 && (value[0] & 0x80) != 0) {
            byte[] padded = new byte[value.length + 1];
            System.arraycopy(value, 0, padded, 1, value.length);
            value = padded;
        }
        return derEncode(0x02, value);
    }

    private static byte[] derEncodeSequence(byte[]... elements) {
        byte[] content = concat(elements);
        return derEncode(0x30, content);
    }

    private static byte[] derEncode(int tag, byte[] content) {
        int length = content.length;
        byte[] lengthBytes;
        if (length < 128) {
            lengthBytes = new byte[]{(byte) length};
        } else if (length < 256) {
            lengthBytes = new byte[]{(byte) 0x81, (byte) length};
        } else {
            lengthBytes = new byte[]{(byte) 0x82, (byte) (length >> 8), (byte) (length & 0xFF)};
        }
        byte[] result = new byte[1 + lengthBytes.length + content.length];
        result[0] = (byte) tag;
        System.arraycopy(lengthBytes, 0, result, 1, lengthBytes.length);
        System.arraycopy(content, 0, result, 1 + lengthBytes.length, content.length);
        return result;
    }

    private static byte[] padToLength(byte[] data, int targetLength) {
        if (data.length == targetLength) {
            return data;
        }
        if (data.length > targetLength) {
            byte[] result = new byte[targetLength];
            System.arraycopy(data, data.length - targetLength, result, 0, targetLength);
            return result;
        }
        byte[] result = new byte[targetLength];
        System.arraycopy(data, 0, result, targetLength - data.length, data.length);
        return result;
    }

    private static String requireString(Map<String, Object> jwk, String field, @Nullable AdcpUse expectedUse)
            throws VerificationException {
        Object value = jwk.get(field);
        if (value == null) {
            throw new VerificationException(
                    errorCode(expectedUse, "invalid"),
                    "JWK missing required field: " + field);
        }
        if (!(value instanceof String str)) {
            throw new VerificationException(
                    errorCode(expectedUse, "invalid"),
                    "JWK field '" + field + "' must be a string");
        }
        return str;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, offset, a.length);
            offset += a.length;
        }
        return result;
    }
}