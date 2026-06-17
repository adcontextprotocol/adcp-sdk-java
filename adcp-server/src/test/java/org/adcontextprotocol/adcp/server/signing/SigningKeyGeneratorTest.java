package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.junit.jupiter.api.Test;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.EdECPrivateKey;

import static org.junit.jupiter.api.Assertions.*;

class SigningKeyGeneratorTest {

    @Test
    void generateEd25519_returnsValidKeypair() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEd25519();

        assertNotNull(kp.privateKey());
        assertNotNull(kp.verificationKey());
        assertNotNull(kp.jwkJson());

        assertTrue(kp.privateKey() instanceof EdECPrivateKey,
                "Expected EdECPrivateKey, got: " + kp.privateKey().getClass().getSimpleName());
    }

    @Test
    void generateEd25519_withCustomKid() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEd25519("my-test-kid");

        assertEquals("my-test-kid", kp.verificationKey().kid());
        assertTrue(kp.jwkJson().contains("\"kid\":\"my-test-kid\""));
    }

    @Test
    void generateEd25519_jwkContainsRequiredFields() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEd25519();

        String jwk = kp.jwkJson();
        assertTrue(jwk.contains("\"kty\":\"OKP\""));
        assertTrue(jwk.contains("\"crv\":\"Ed25519\""));
        assertTrue(jwk.contains("\"alg\":\"ed25519\""));
        assertTrue(jwk.contains("\"use\":\"sig\""));
        assertTrue(jwk.contains("\"adcp_use\":\"adcp_req\""));
        assertTrue(jwk.contains("\"x\":\""));
    }

    @Test
    void generateEd25519_verificationKeyMatches() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEd25519();

        VerificationKey vk = kp.verificationKey();
        assertEquals("Ed25519", vk.algorithm());
        assertEquals("Ed25519", vk.crv());
    }

    @Test
    void generateEs256_returnsValidKeypair() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEs256();

        assertNotNull(kp.privateKey());
        assertNotNull(kp.verificationKey());
        assertNotNull(kp.jwkJson());

        assertTrue(kp.privateKey() instanceof ECPrivateKey);
        ECPrivateKey ecKey = (ECPrivateKey) kp.privateKey();
        assertEquals(256, ecKey.getParams().getOrder().bitLength());
    }

    @Test
    void generateEs256_withCustomKid() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEs256("my-es256-kid");

        assertEquals("my-es256-kid", kp.verificationKey().kid());
        assertTrue(kp.jwkJson().contains("\"kid\":\"my-es256-kid\""));
    }

    @Test
    void generateEs256_jwkContainsRequiredFields() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEs256();

        String jwk = kp.jwkJson();
        assertTrue(jwk.contains("\"kty\":\"EC\""));
        assertTrue(jwk.contains("\"crv\":\"P-256\""));
        assertTrue(jwk.contains("\"alg\":\"ecdsa-p256-sha256\""));
        assertTrue(jwk.contains("\"use\":\"sig\""));
        assertTrue(jwk.contains("\"adcp_use\":\"adcp_req\""));
        assertTrue(jwk.contains("\"x\":\""));
        assertTrue(jwk.contains("\"y\":\""));
    }

    @Test
    void generateEs256_verificationKeyMatches() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEs256();

        VerificationKey vk = kp.verificationKey();
        assertEquals("EC", vk.algorithm());
        assertEquals("P-256", vk.crv());
    }

    @Test
    void generateEs384_returnsValidKeypair() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEs384();

        assertNotNull(kp.privateKey());
        assertNotNull(kp.verificationKey());
        assertNotNull(kp.jwkJson());

        assertTrue(kp.privateKey() instanceof ECPrivateKey);
        ECPrivateKey ecKey = (ECPrivateKey) kp.privateKey();
        assertEquals(384, ecKey.getParams().getOrder().bitLength());
    }

    @Test
    void generateEs384_jwkContainsRequiredFields() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEs384();

        String jwk = kp.jwkJson();
        assertTrue(jwk.contains("\"kty\":\"EC\""));
        assertTrue(jwk.contains("\"crv\":\"P-384\""));
        assertTrue(jwk.contains("\"alg\":\"ecdsa-p384-sha384\""));
        assertTrue(jwk.contains("\"use\":\"sig\""));
        assertTrue(jwk.contains("\"adcp_use\":\"adcp_req\""));
    }

    @Test
    void generateEs384_verificationKeyMatches() {
        SigningKeyGenerator.GeneratedKeypair kp = SigningKeyGenerator.generateEs384();

        VerificationKey vk = kp.verificationKey();
        assertEquals("EC", vk.algorithm());
        assertEquals("P-384", vk.crv());
    }

    @Test
    void generatedKeypairsAreUnique() {
        SigningKeyGenerator.GeneratedKeypair kp1 = SigningKeyGenerator.generateEd25519();
        SigningKeyGenerator.GeneratedKeypair kp2 = SigningKeyGenerator.generateEd25519();

        assertFalse(java.util.Arrays.equals(kp1.verificationKey().publicKeyBytes(),
                kp2.verificationKey().publicKeyBytes()),
                "Two generated keypairs should differ");
    }

    @Test
    void generatedKeypair_nullChecks() {
        assertThrows(NullPointerException.class, () ->
                new SigningKeyGenerator.GeneratedKeypair(null, null, null));
    }

    @Test
    void trimLeadingZeros_basic() {
        assertArrayEquals(new byte[]{1}, SigningKeyGenerator.trimLeadingZeros(new byte[]{0, 1}));
        assertArrayEquals(new byte[]{1, 2}, SigningKeyGenerator.trimLeadingZeros(new byte[]{0, 0, 1, 2}));
        assertArrayEquals(new byte[]{1}, SigningKeyGenerator.trimLeadingZeros(new byte[]{1}));
        assertArrayEquals(new byte[]{-1, 0}, SigningKeyGenerator.trimLeadingZeros(new byte[]{-1, 0}));
    }
}