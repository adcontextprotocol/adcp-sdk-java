package org.adcontextprotocol.adcp.server.signing;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.EdECPrivateKey;

import static org.junit.jupiter.api.Assertions.*;

class InProcessKeyGeneratorTest {

    @Test
    void generateEd25519_returnsValidKeyPair() {
        KeyPair kp = InProcessKeyGenerator.generateEd25519();

        assertNotNull(kp);
        assertNotNull(kp.getPrivate());
        assertNotNull(kp.getPublic());
        assertTrue(kp.getPrivate().getAlgorithm().equals("Ed25519") || kp.getPrivate().getAlgorithm().equals("EdDSA"),
                "Expected Ed25519 or EdDSA algorithm, got: " + kp.getPrivate().getAlgorithm());
        assertTrue(kp.getPublic().getAlgorithm().equals("Ed25519") || kp.getPublic().getAlgorithm().equals("EdDSA"),
                "Expected Ed25519 or EdDSA algorithm, got: " + kp.getPublic().getAlgorithm());
    }

    @Test
    void generateEd25519_producesDifferentKeyPairs() {
        KeyPair kp1 = InProcessKeyGenerator.generateEd25519();
        KeyPair kp2 = InProcessKeyGenerator.generateEd25519();

        assertArrayEquals(kp1.getPrivate().getEncoded(), kp1.getPrivate().getEncoded());
        assertFalse(java.util.Arrays.equals(kp1.getPrivate().getEncoded(), kp2.getPrivate().getEncoded()),
                "Two generated Ed25519 keypairs should differ");
    }

    @Test
    void generateES256_returnsValidKeyPair() {
        KeyPair kp = InProcessKeyGenerator.generateES256();

        assertNotNull(kp);
        assertNotNull(kp.getPrivate());
        assertNotNull(kp.getPublic());
        assertEquals("EC", kp.getPrivate().getAlgorithm());
        assertEquals("EC", kp.getPublic().getAlgorithm());
    }

    @Test
    void generateES384_returnsValidKeyPair() {
        KeyPair kp = InProcessKeyGenerator.generateES384();

        assertNotNull(kp);
        assertNotNull(kp.getPrivate());
        assertNotNull(kp.getPublic());
        assertEquals("EC", kp.getPrivate().getAlgorithm());
        assertEquals("EC", kp.getPublic().getAlgorithm());
    }

    @Test
    void generateES256_keyIsP256() {
        KeyPair kp = InProcessKeyGenerator.generateES256();

        assertTrue(kp.getPrivate() instanceof ECPrivateKey);
        ECPrivateKey ecKey = (ECPrivateKey) kp.getPrivate();
        assertEquals(256, ecKey.getParams().getOrder().bitLength());
    }

    @Test
    void generateES384_keyIsP384() {
        KeyPair kp = InProcessKeyGenerator.generateES384();

        assertTrue(kp.getPrivate() instanceof ECPrivateKey);
        ECPrivateKey ecKey = (ECPrivateKey) kp.getPrivate();
        assertEquals(384, ecKey.getParams().getOrder().bitLength());
    }

    @Test
    void generateEd25519_privateKeyIsEdECKey() {
        KeyPair kp = InProcessKeyGenerator.generateEd25519();

        assertTrue(kp.getPrivate() instanceof EdECPrivateKey);
    }
}