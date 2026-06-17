package org.adcontextprotocol.adcp.server.signing;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECGenParameterSpec;

/**
 * Utility for generating test keypairs (Ed25519 and ES256).
 *
 * <p>This is for testing and development only — production deployments should
 * use KMS-managed keys.
 */
public final class InProcessKeyGenerator {

    private InProcessKeyGenerator() {}

    /**
     * Generate an Ed25519 keypair.
     *
     * @return the generated keypair
     */
    public static KeyPair generateEd25519() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not available (requires JDK 15+)", e);
        }
    }

    /**
     * Generate an ECDSA P-256 (ES256) keypair.
     *
     * @return the generated keypair
     */
    public static KeyPair generateES256() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("EC P-256 not available", e);
        }
    }

    /**
     * Generate an ECDSA P-384 (ES384) keypair.
     *
     * @return the generated keypair
     */
    public static KeyPair generateES384() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp384r1"));
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("EC P-384 not available", e);
        }
    }
}