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

        String kid = requireString(jwk, "kid");
        String kty = requireString(jwk, "kty");

        validateAdcpUse(jwk, expectedUse);
        validateKeyOps(jwk);

        return switch (kty) {
            case "OKP" -> parseOkp(jwk, kid);
            case "EC" -> parseEc(jwk, kid);
            case "RSA" -> parseRsa(jwk, kid);
            default -> throw new VerificationException(
                    "webhook_signature_invalid",
                    "Unsupported JWK kty: " + kty);
        };
    }

    private static VerificationKey parseOkp(Map<String, Object> jwk, String kid)
            throws VerificationException {
        String crv = requireString(jwk, "crv");
        if (!"Ed25519".equals(crv)) {
            throw new VerificationException(
                    "webhook_signature_invalid",
                    "Unsupported OKP curve: " + crv);
        }
        String xB64 = requireString(jwk, "x");
        byte[] x = base64urlDecode(xB64);
        if (x.length != 32) {
            throw new VerificationException(
                    "webhook_signature_invalid",
                    "Ed25519 public key must be 32 bytes, got " + x.length);
        }

        byte[] der = encodeEd25519Der(x);
        return new VerificationKey(kid, "Ed25519", der, "Ed25519");
    }

    private static VerificationKey parseEc(Map<String, Object> jwk, String kid)
            throws VerificationException {
        String crv = requireString(jwk, "crv");
        String xB64 = requireString(jwk, "x");
        String yB64 = requireString(jwk, "y");

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
                    "webhook_signature_invalid",
                    "Unsupported EC curve: " + crv);
        }

        byte[] xBytes = base64urlDecode(xB64);
        byte[] yBytes = base64urlDecode(yB64);

        int expectedOctets = fieldSizeBits / 8;
        xBytes = padToLength(xBytes, expectedOctets);
        yBytes = padToLength(yBytes, expectedOctets);

        byte[] der = encodeEcDer(crv, xBytes, yBytes);
        return new VerificationKey(kid, algorithm, der, crv);
    }

    private static VerificationKey parseRsa(Map<String, Object> jwk, String kid)
            throws VerificationException {
        String nB64 = requireString(jwk, "n");
        String eB64 = requireString(jwk, "e");

        byte[] n = base64urlDecode(nB64);
        byte[] e = base64urlDecode(eB64);

        byte[] der = encodeRsaDer(n, e);
        return new VerificationKey(kid, "RSA", der, null);
    }

    private static void validateAdcpUse(Map<String, Object> jwk, @Nullable AdcpUse expectedUse)
            throws VerificationException {
        if (expectedUse == null) {
            return;
        }
        Object adcpUseObj = jwk.get("adcp_use");
        if (adcpUseObj == null) {
            throw new VerificationException(
                    "webhook_signature_key_purpose_invalid",
                    "JWK missing required 'adcp_use' parameter");
        }
        String adcpUseStr = adcpUseObj.toString();
        AdcpUse jwkUse;
        try {
            jwkUse = AdcpUse.fromWireName(adcpUseStr);
        } catch (IllegalArgumentException e) {
            throw new VerificationException(
                    "webhook_signature_key_purpose_invalid",
                    "Unknown adcp_use value: " + adcpUseStr);
        }
        if (jwkUse != expectedUse) {
            throw new VerificationException(
                    "webhook_signature_key_purpose_invalid",
                    "JWK adcp_use=" + adcpUseStr + " does not match expected "
                            + expectedUse.wireName());
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateKeyOps(Map<String, Object> jwk) throws VerificationException {
        Object keyOpsObj = jwk.get("key_ops");
        if (keyOpsObj == null) {
            return;
        }
        if (!(keyOpsObj instanceof List<?> ops)) {
            throw new VerificationException(
                    "webhook_signature_invalid",
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
                    "webhook_signature_key_purpose_invalid",
                    "JWK key_ops does not include 'verify'");
        }
    }

    // -- Base64url decoding --

    private static byte[] base64urlDecode(String b64) throws VerificationException {
        try {
            String padded = b64;
            int padNeeded = (4 - padded.length() % 4) % 4;
            padded += "=".repeat(padNeeded);
            return Base64.getUrlDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            throw new VerificationException(
                    "webhook_signature_invalid",
                    "Invalid base64url encoding", e);
        }
    }

    // -- DER encoding helpers --

    private static byte[] encodeEd25519Der(byte[] rawKey) throws VerificationException {
        byte[] algorithmIdentifier = hexToBytes("30213006062B6570050100");
        byte[] bitString = wrapBitString(rawKey);
        return concat(algorithmIdentifier, bitString);
    }

    private static byte[] encodeEcDer(String crv, byte[] x, byte[] y) throws VerificationException {
        String oid;
        if ("P-256".equals(crv)) {
            oid = "06082A8648CE3D030107";
        } else if ("P-384".equals(crv)) {
            oid = "06052B81040022";
        } else {
            throw new VerificationException(
                    "webhook_signature_invalid",
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

    private static String requireString(Map<String, Object> jwk, String field)
            throws VerificationException {
        Object value = jwk.get(field);
        if (value == null) {
            throw new VerificationException(
                    "webhook_signature_invalid",
                    "JWK missing required field: " + field);
        }
        if (!(value instanceof String str)) {
            throw new VerificationException(
                    "webhook_signature_invalid",
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