package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.VerificationKey;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Utility for generating Ed25519, ES256, and ES384 keypairs with JWK output.
 *
 * <p>Used for development/testing key generation, the {@code adcp-keygen} CLI
 * command, and pre-deploy key provisioning.
 *
 * <p>Production deployments should use KMS-managed keys, not in-process keys.
 */
public final class SigningKeyGenerator {

    private SigningKeyGenerator() {}

    /**
     * Generated keypair result containing the private key, verification key,
     * and the public key as a JWK JSON string.
     *
     * @param privateKey      the JCA private key
     * @param verificationKey the AdCP verification key
     * @param jwkJson         the public key as a JWK JSON string
     */
    public record GeneratedKeypair(
            PrivateKey privateKey,
            VerificationKey verificationKey,
            String jwkJson
    ) {
        public GeneratedKeypair {
            if (privateKey == null) throw new NullPointerException("privateKey");
            if (verificationKey == null) throw new NullPointerException("verificationKey");
            if (jwkJson == null) throw new NullPointerException("jwkJson");
        }
    }

    /**
     * Generate an Ed25519 keypair.
     *
     * @return the generated keypair with JWK representation
     */
    public static GeneratedKeypair generateEd25519() {
        return generateEd25519("key-ed25519-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Generate an Ed25519 keypair with a specific kid.
     *
     * @param kid the key identifier
     * @return the generated keypair with JWK representation
     */
    public static GeneratedKeypair generateEd25519(String kid) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = gen.generateKeyPair();

            EdECPublicKey pubKey = (EdECPublicKey) kp.getPublic();
            byte[] rawPublicKey = pubKey.getEncoded();

            VerificationKey vk = new VerificationKey(kid, "Ed25519", rawPublicKey, "Ed25519");

            String x = base64Url(rawPublicKey);
            String jwkJson = buildJwkJson(kid, "OKP", "Ed25519", x, null, "ed25519", "sig", "adcp_req");

            return new GeneratedKeypair(kp.getPrivate(), vk, jwkJson);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not available (requires JDK 15+)", e);
        }
    }

    /**
     * Generate an ES256 (P-256) keypair.
     *
     * @return the generated keypair with JWK representation
     */
    public static GeneratedKeypair generateEs256() {
        return generateEs256("key-es256-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Generate an ES256 (P-256) keypair with a specific kid.
     *
     * @param kid the key identifier
     * @return the generated keypair with JWK representation
     */
    public static GeneratedKeypair generateEs256(String kid) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = gen.generateKeyPair();

            ECPublicKey pubKey = (ECPublicKey) kp.getPublic();
            byte[] xBytes = trimLeadingZeros(pubKey.getW().getAffineX().toByteArray());
            byte[] yBytes = trimLeadingZeros(pubKey.getW().getAffineY().toByteArray());
            byte[] spkiDer = pubKey.getEncoded();

            VerificationKey vk = new VerificationKey(kid, "EC", spkiDer, "P-256");

            String x = base64Url(xBytes);
            String y = base64Url(yBytes);
            String jwkJson = buildJwkJson(kid, "EC", "P-256", x, y, "ecdsa-p256-sha256", "sig", "adcp_req");

            return new GeneratedKeypair(kp.getPrivate(), vk, jwkJson);
        } catch (Exception e) {
            throw new IllegalStateException("EC P-256 not available", e);
        }
    }

    /**
     * Generate an ES384 (P-384) keypair.
     *
     * @return the generated keypair with JWK representation
     */
    public static GeneratedKeypair generateEs384() {
        return generateEs384("key-es384-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Generate an ES384 (P-384) keypair with a specific kid.
     *
     * @param kid the key identifier
     * @return the generated keypair with JWK representation
     */
    public static GeneratedKeypair generateEs384(String kid) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp384r1"));
            KeyPair kp = gen.generateKeyPair();

            ECPublicKey pubKey = (ECPublicKey) kp.getPublic();
            byte[] xBytes = trimLeadingZeros(pubKey.getW().getAffineX().toByteArray());
            byte[] yBytes = trimLeadingZeros(pubKey.getW().getAffineY().toByteArray());
            byte[] spkiDer = pubKey.getEncoded();

            VerificationKey vk = new VerificationKey(kid, "EC", spkiDer, "P-384");

            String x = base64Url(xBytes);
            String y = base64Url(yBytes);
            String jwkJson = buildJwkJson(kid, "EC", "P-384", x, y, "ecdsa-p384-sha384", "sig", "adcp_req");

            return new GeneratedKeypair(kp.getPrivate(), vk, jwkJson);
        } catch (Exception e) {
            throw new IllegalStateException("EC P-384 not available", e);
        }
    }

    private static String buildJwkJson(String kid, String kty, String crv, String x,
                                        String y, String alg, String use, String adcpUse) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"kid\":\"").append(escapeJson(kid)).append("\"");
        sb.append(",\"kty\":\"").append(kty).append("\"");
        sb.append(",\"crv\":\"").append(crv).append("\"");
        sb.append(",\"x\":\"").append(x).append("\"");
        if (y != null) {
            sb.append(",\"y\":\"").append(y).append("\"");
        }
        sb.append(",\"alg\":\"").append(alg).append("\"");
        sb.append(",\"use\":\"").append(use).append("\"");
        sb.append(",\"adcp_use\":\"").append(adcpUse).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static byte[] trimLeadingZeros(byte[] bytes) {
        int start = 0;
        while (start < bytes.length - 1 && bytes[start] == 0) {
            start++;
        }
        if (start == 0) return bytes;
        byte[] result = new byte[bytes.length - start];
        System.arraycopy(bytes, start, result, 0, result.length);
        return result;
    }
}