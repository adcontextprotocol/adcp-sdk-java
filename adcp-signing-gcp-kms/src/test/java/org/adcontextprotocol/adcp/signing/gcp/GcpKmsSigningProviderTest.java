package org.adcontextprotocol.adcp.signing.gcp;

import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.PublicKey;
import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SigningContext;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.adcontextprotocol.adcp.signing.SigningInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GcpKmsSigningProviderTest {

    private static final String REQUEST_KEY_VERSION_PATH =
            "projects/my-project/locations/us-east1/keyRings/adcp-keys/cryptoKeys/request-signing/cryptoKeyVersions/1";
    private static final String WEBHOOK_KEY_VERSION_PATH =
            "projects/my-project/locations/us-east1/keyRings/adcp-keys/cryptoKeys/webhook-signing/cryptoKeyVersions/1";

    @Mock
    private KeyManagementServiceClient kmsClient;

    private record TestSigningInput(String method, String targetUri, byte[] body, Map<String, String> headers)
            implements SigningInput {}

    @Test
    void builder_requiresAtLeastOneKeyVersionPath() {
        assertThrows(IllegalStateException.class, () ->
                GcpKmsSigningProvider.builder()
                        .kmsClient(kmsClient)
                        .build());
    }

    @Test
    void builder_rejectsBlankKeyVersionPath() {
        assertThrows(IllegalArgumentException.class, () ->
                GcpKmsSigningProvider.builder()
                        .keyVersionPath(AdcpUse.REQUEST_SIGNING, "  "));
    }

    @Test
    void builder_rejectsNullKeyVersionPath() {
        assertThrows(NullPointerException.class, () ->
                GcpKmsSigningProvider.builder()
                        .keyVersionPath(AdcpUse.REQUEST_SIGNING, null));
    }

    @Test
    void builder_rejectsNullUse() {
        assertThrows(NullPointerException.class, () ->
                GcpKmsSigningProvider.builder()
                        .keyVersionPath(null, REQUEST_KEY_VERSION_PATH));
    }

    @Test
    void builder_rejectsNullFingerprint() {
        assertThrows(NullPointerException.class, () ->
                GcpKmsSigningProvider.builder()
                        .committedPublicKeyFingerprint(AdcpUse.REQUEST_SIGNING, null));
    }

    @Test
    void builder_rejectsBlankCredentialsPath() {
        assertThrows(IllegalArgumentException.class, () ->
                GcpKmsSigningProvider.builder()
                        .credentialsPath("  "));
    }

    @Test
    void lazyInit_kmsClientNotCreatedUntilFirstSign() {
        GcpKmsSigningProvider provider = GcpKmsSigningProvider.builder()
                .keyVersionPath(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_VERSION_PATH)
                .kmsClient(kmsClient)
                .build();

        assertFalse(provider.isInitialized());
        verifyNoInteractions(kmsClient);
    }

    @Test
    void keyVersionPathMapping_selectsCorrectKeyPerUse() throws Exception {
        PublicKey ed25519PubKey = PublicKey.newBuilder()
                .setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_ED25519)
                .setPem(generateEd25519Pem())
                .build();

        when(kmsClient.getPublicKey(eq(REQUEST_KEY_VERSION_PATH))).thenReturn(ed25519PubKey);
        when(kmsClient.getPublicKey(eq(WEBHOOK_KEY_VERSION_PATH))).thenReturn(ed25519PubKey);

        GcpKmsSigningProvider provider = GcpKmsSigningProvider.builder()
                .keyVersionPath(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_VERSION_PATH)
                .keyVersionPath(AdcpUse.WEBHOOK_SIGNING, WEBHOOK_KEY_VERSION_PATH)
                .kmsClient(kmsClient)
                .build();

        provider.initialize();

        Map<AdcpUse, CryptoKeyVersionAlgorithm> algs = provider.getAlgorithms();
        assertEquals(2, algs.size());
        assertEquals(CryptoKeyVersionAlgorithm.EC_SIGN_ED25519, algs.get(AdcpUse.REQUEST_SIGNING));
        assertEquals(CryptoKeyVersionAlgorithm.EC_SIGN_ED25519, algs.get(AdcpUse.WEBHOOK_SIGNING));
    }

    @Test
    void ecdsaDerToRaw_convertsCorrectly() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(256);
        KeyPair keyPair = kpg.generateKeyPair();
        Signature ecdsa = Signature.getInstance("SHA256withECDSA");
        ecdsa.initSign(keyPair.getPrivate());
        ecdsa.update("test message".getBytes(StandardCharsets.UTF_8));
        byte[] derSig = ecdsa.sign();

        byte[] rawSig = GcpKmsSigningProvider.ecdsaDerToRaw(derSig, 32);

        assertEquals(64, rawSig.length, "P-256 raw signature should be 64 bytes (r=32 || s=32)");
    }

    @Test
    void ecdsaDerToRaw_handlesP384() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(384);
        KeyPair keyPair = kpg.generateKeyPair();

        Signature ecdsa = Signature.getInstance("SHA384withECDSA");
        ecdsa.initSign(keyPair.getPrivate());
        ecdsa.update("test p-384".getBytes(StandardCharsets.UTF_8));
        byte[] derSig = ecdsa.sign();

        byte[] rawSig = GcpKmsSigningProvider.ecdsaDerToRaw(derSig, 48);

        assertEquals(96, rawSig.length, "P-384 raw signature should be 96 bytes (r=48 || s=48)");
    }

    @Test
    void ecdsaDerToRaw_rejectsInvalidDerTag() {
        byte[] invalid = new byte[]{0x01, 0x02, 0x03};
        assertThrows(IllegalArgumentException.class,
                () -> GcpKmsSigningProvider.ecdsaDerToRaw(invalid, 32));
    }

    @Test
    void tripwire_mismatchThrowsSigningException() throws Exception {
        String pem = generateEd25519Pem();

        byte[] fakePublicKeyBytes = new byte[44];
        new java.security.SecureRandom().nextBytes(fakePublicKeyBytes);
        String fakeFingerprint = sha256Hex(fakePublicKeyBytes);

        PublicKey mockPublicKey = PublicKey.newBuilder()
                .setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_ED25519)
                .setPem(pem)
                .build();
        when(kmsClient.getPublicKey(eq(REQUEST_KEY_VERSION_PATH))).thenReturn(mockPublicKey);

        GcpKmsSigningProvider provider = GcpKmsSigningProvider.builder()
                .keyVersionPath(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_VERSION_PATH)
                .committedPublicKeyFingerprint(AdcpUse.REQUEST_SIGNING, fakeFingerprint)
                .kmsClient(kmsClient)
                .build();

        SigningException ex = assertThrows(SigningException.class, provider::initialize);
        assertTrue(ex.getMessage().contains("fingerprint mismatch"),
                "Expected fingerprint mismatch message, got: " + ex.getMessage());
    }

    @Test
    void tripwire_matchingFingerprintSucceeds() throws Exception {
        String pem = generateEd25519Pem();
        byte[] spkiDer = GcpKmsSigningProvider.extractSpkiDer(pem);
        String fingerprint = sha256Hex(spkiDer);

        PublicKey mockPublicKey = PublicKey.newBuilder()
                .setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_ED25519)
                .setPem(pem)
                .build();
        when(kmsClient.getPublicKey(eq(REQUEST_KEY_VERSION_PATH))).thenReturn(mockPublicKey);

        GcpKmsSigningProvider provider = GcpKmsSigningProvider.builder()
                .keyVersionPath(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_VERSION_PATH)
                .committedPublicKeyFingerprint(AdcpUse.REQUEST_SIGNING, fingerprint)
                .kmsClient(kmsClient)
                .build();

        assertDoesNotThrow(provider::initialize);
        assertTrue(provider.isInitialized());
    }

    @Test
    void kmsException_wrappedInSigningException() throws Exception {
        when(kmsClient.getPublicKey(eq(REQUEST_KEY_VERSION_PATH)))
                .thenThrow(new RuntimeException("Access denied"));

        GcpKmsSigningProvider provider = GcpKmsSigningProvider.builder()
                .keyVersionPath(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_VERSION_PATH)
                .kmsClient(kmsClient)
                .build();

        SigningContext context = SigningContext.builder(AdcpUse.REQUEST_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        SigningInput input = new TestSigningInput("POST", "https://example.com/path", new byte[0], headers);

        SigningException ex = assertThrows(SigningException.class,
                () -> provider.sign(context, input));
        assertTrue(ex.getMessage().contains("Failed to initialize GCP KMS"),
                "Expected GCP KMS init error, got: " + ex.getMessage());
    }

    @Test
    void redactKeyVersionPath_replacesProjectId() {
        String path = "projects/my-secret-project/locations/us-east1/keyRings/adcp-keys/cryptoKeys/req/cryptoKeyVersions/1";
        String redacted = GcpKmsSigningProvider.redactKeyVersionPath(path);
        assertFalse(redacted.contains("my-secret-project"),
                "Project ID should be redacted: " + redacted);
        assertTrue(redacted.contains("<redacted>"),
                "Should contain <redacted>: " + redacted);
    }

    @Test
    void adcpAlgorithmFor_mapsCorrectly() throws SigningException {
        assertEquals("ed25519", GcpKmsSigningProvider.adcpAlgorithmFor(CryptoKeyVersionAlgorithm.EC_SIGN_ED25519));
        assertEquals("ecdsa-p256-sha256", GcpKmsSigningProvider.adcpAlgorithmFor(CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256));
        assertEquals("ecdsa-p384-sha384", GcpKmsSigningProvider.adcpAlgorithmFor(CryptoKeyVersionAlgorithm.EC_SIGN_P384_SHA384));
    }

    @Test
    void adcpAlgorithmFor_rejectsUnsupported() {
        assertThrows(SigningException.class,
                () -> GcpKmsSigningProvider.adcpAlgorithmFor(CryptoKeyVersionAlgorithm.RSA_SIGN_PKCS1_2048_SHA256));
    }

    @Test
    void sign_noKeyVersionPathForUse_throws() throws Exception {
        String pem = generateEd25519Pem();
        PublicKey mockPublicKey = PublicKey.newBuilder()
                .setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_ED25519)
                .setPem(pem)
                .build();
        when(kmsClient.getPublicKey(eq(REQUEST_KEY_VERSION_PATH))).thenReturn(mockPublicKey);

        GcpKmsSigningProvider provider = GcpKmsSigningProvider.builder()
                .keyVersionPath(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_VERSION_PATH)
                .kmsClient(kmsClient)
                .build();

        provider.initialize();

        SigningContext webhookContext = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        SigningInput input = new TestSigningInput("POST", "https://example.com/webhook",
                "{\"test\":true}".getBytes(StandardCharsets.UTF_8), headers);

        SigningException ex = assertThrows(SigningException.class,
                () -> provider.sign(webhookContext, input));
        assertTrue(ex.getMessage().contains("No key version path configured"),
                "Expected 'No key version path configured' message, got: " + ex.getMessage());
    }

    private static String generateEd25519Pem() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = kpg.generateKeyPair();
        byte[] encoded = keyPair.getPublic().getEncoded();
        String base64 = Base64.getMimeEncoder().encodeToString(encoded);
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN PUBLIC KEY-----\n");
        int lineLen = 0;
        for (int i = 0; i < base64.length(); i++) {
            pem.append(base64.charAt(i));
            lineLen++;
            if (lineLen == 64 || i == base64.length() - 1) {
                pem.append('\n');
                lineLen = 0;
            }
        }
        pem.append("-----END PUBLIC KEY-----\n");
        return pem.toString();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}