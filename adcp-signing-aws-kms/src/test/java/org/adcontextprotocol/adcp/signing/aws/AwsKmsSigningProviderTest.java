package org.adcontextprotocol.adcp.signing.aws;

import org.adcontextprotocol.adcp.server.signing.AdcpSignatureProfile;
import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SigningContext;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.adcontextprotocol.adcp.signing.SigningInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwsKmsSigningProviderTest {

    private static final String REQUEST_KEY_ARN = "arn:aws:kms:us-east-1:123456789012:key/aaaa-bbbb-cccc-dddd";
    private static final String WEBHOOK_KEY_ARN = "arn:aws:kms:us-east-1:123456789012:key/eeee-ffff-0000-1111";

    @Mock
    private KmsClient kmsClient;

    private record TestSigningInput(String method, String targetUri, byte[] body, Map<String, String> headers)
            implements SigningInput {}

    @Test
    void builder_requiresAtLeastOneKeyArn() {
        assertThrows(IllegalStateException.class, () ->
                AwsKmsSigningProvider.builder()
                        .region("us-east-1")
                        .kmsClient(kmsClient)
                        .build());
    }

    @Test
    void builder_rejectsBlankKeyArn() {
        assertThrows(IllegalArgumentException.class, () ->
                AwsKmsSigningProvider.builder()
                        .keyArn(AdcpUse.REQUEST_SIGNING, "  "));
    }

    @Test
    void builder_rejectsNullKeyArn() {
        assertThrows(NullPointerException.class, () ->
                AwsKmsSigningProvider.builder()
                        .keyArn(AdcpUse.REQUEST_SIGNING, null));
    }

    @Test
    void builder_rejectsNullUse() {
        assertThrows(NullPointerException.class, () ->
                AwsKmsSigningProvider.builder()
                        .keyArn(null, REQUEST_KEY_ARN));
    }

    @Test
    void builder_rejectsBlankRegion() {
        assertThrows(IllegalArgumentException.class, () ->
                AwsKmsSigningProvider.builder()
                        .keyArn(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_ARN)
                        .region("  "));
    }

    @Test
    void builder_rejectsNullFingerprint() {
        assertThrows(NullPointerException.class, () ->
                AwsKmsSigningProvider.builder()
                        .committedPublicKeyFingerprint(AdcpUse.REQUEST_SIGNING, null));
    }

    @Test
    void lazyInit_kmsClientNotCreatedUntilFirstSign() {
        AwsKmsSigningProvider provider = AwsKmsSigningProvider.builder()
                .keyArn(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_ARN)
                .region("us-east-1")
                .kmsClient(kmsClient)
                .build();

        assertFalse(provider.isInitialized());
        verifyNoInteractions(kmsClient);
    }

    @Test
    void keyArnMapping_selectsCorrectKeyPerUse() throws SigningException {
        GetPublicKeyResponse requestResponse = GetPublicKeyResponse.builder()
                .publicKey(SdkBytes.fromByteArray(new byte[32]))
                .signingAlgorithms(List.of(SigningAlgorithmSpec.ED25519_SHA_512))
                .build();

        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class))).thenReturn(requestResponse);

        AwsKmsSigningProvider provider = AwsKmsSigningProvider.builder()
                .keyArn(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_ARN)
                .keyArn(AdcpUse.WEBHOOK_SIGNING, WEBHOOK_KEY_ARN)
                .region("us-east-1")
                .kmsClient(kmsClient)
                .build();

        provider.initialize();

        Map<AdcpUse, SigningAlgorithmSpec> algs = provider.getAlgorithms();
        assertEquals(2, algs.size());
        assertEquals(SigningAlgorithmSpec.ED25519_SHA_512, algs.get(AdcpUse.REQUEST_SIGNING));
        assertEquals(SigningAlgorithmSpec.ED25519_SHA_512, algs.get(AdcpUse.WEBHOOK_SIGNING));
    }

    @Test
    void ecdsaDerToRaw_convertsCorrectly() {
        byte[] derSig;
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(256);
            KeyPair keyPair = kpg.generateKeyPair();
            java.security.Signature ecdsa = java.security.Signature.getInstance("SHA256withECDSA");
            ecdsa.initSign(keyPair.getPrivate());
            ecdsa.update("test message".getBytes(StandardCharsets.UTF_8));
            derSig = ecdsa.sign();
        } catch (Exception e) {
            fail("Failed to generate ECDSA signature: " + e.getMessage());
            return;
        }

        byte[] rawSig = AwsKmsSigningProvider.ecdsaDerToRaw(derSig, 32);

        assertEquals(64, rawSig.length, "P-256 raw signature should be 64 bytes (r=32 || s=32)");
    }

    @Test
    void ecdsaDerToRaw_handlesP384() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(384);
        KeyPair keyPair = kpg.generateKeyPair();

        java.security.Signature ecdsa = java.security.Signature.getInstance("SHA384withECDSA");
        ecdsa.initSign(keyPair.getPrivate());
        ecdsa.update("test p-384".getBytes(StandardCharsets.UTF_8));
        byte[] derSig = ecdsa.sign();

        byte[] rawSig = AwsKmsSigningProvider.ecdsaDerToRaw(derSig, 48);

        assertEquals(96, rawSig.length, "P-384 raw signature should be 96 bytes (r=48 || s=48)");
    }

    @Test
    void ecdsaDerToRaw_rejectsInvalidDerTag() {
        byte[] invalid = new byte[]{0x01, 0x02, 0x03};
        assertThrows(IllegalArgumentException.class,
                () -> AwsKmsSigningProvider.ecdsaDerToRaw(invalid, 32));
    }

    @Test
    void tripwire_mismatchThrowsSigningException() throws Exception {
        byte[] realPublicKeyBytes = generateEd25519PublicKeyBytes();
        String realFingerprint = sha256Hex(realPublicKeyBytes);

        byte[] fakePublicKeyBytes = new byte[44];
        new java.security.SecureRandom().nextBytes(fakePublicKeyBytes);
        String fakeFingerprint = sha256Hex(fakePublicKeyBytes);

        GetPublicKeyResponse mockResponse = GetPublicKeyResponse.builder()
                .publicKey(SdkBytes.fromByteArray(realPublicKeyBytes))
                .signingAlgorithms(List.of(SigningAlgorithmSpec.ED25519_SHA_512))
                .build();
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class))).thenReturn(mockResponse);

        AwsKmsSigningProvider provider = AwsKmsSigningProvider.builder()
                .keyArn(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_ARN)
                .committedPublicKeyFingerprint(AdcpUse.REQUEST_SIGNING, fakeFingerprint)
                .region("us-east-1")
                .kmsClient(kmsClient)
                .build();

        SigningException ex = assertThrows(SigningException.class, provider::initialize);
        assertTrue(ex.getMessage().contains("fingerprint mismatch"),
                "Expected fingerprint mismatch message, got: " + ex.getMessage());
    }

    @Test
    void tripwire_matchingFingerprintSucceeds() throws Exception {
        byte[] publicKeyBytes = generateEd25519PublicKeyBytes();
        String fingerprint = sha256Hex(publicKeyBytes);

        GetPublicKeyResponse mockResponse = GetPublicKeyResponse.builder()
                .publicKey(SdkBytes.fromByteArray(publicKeyBytes))
                .signingAlgorithms(List.of(SigningAlgorithmSpec.ED25519_SHA_512))
                .build();
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class))).thenReturn(mockResponse);

        AwsKmsSigningProvider provider = AwsKmsSigningProvider.builder()
                .keyArn(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_ARN)
                .committedPublicKeyFingerprint(AdcpUse.REQUEST_SIGNING, fingerprint)
                .region("us-east-1")
                .kmsClient(kmsClient)
                .build();

        assertDoesNotThrow(provider::initialize);
        assertTrue(provider.isInitialized());
    }

    @Test
    void kmsException_wrappedInSigningException() {
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenThrow(KmsException.builder().message("Access denied").build());

        AwsKmsSigningProvider provider = AwsKmsSigningProvider.builder()
                .keyArn(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_ARN)
                .region("us-east-1")
                .kmsClient(kmsClient)
                .build();

        SigningContext context = SigningContext.builder(AdcpUse.REQUEST_SIGNING).build();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        SigningInput input = new TestSigningInput("POST", "https://example.com/path", new byte[0], headers);

        SigningException ex = assertThrows(SigningException.class,
                () -> provider.sign(context, input));
        assertTrue(ex.getMessage().contains("Failed to initialize AWS KMS"),
                "Expected AWS KMS init error, got: " + ex.getMessage());
    }

    @Test
    void redactArn_replacesAccountId() {
        String arn = "arn:aws:kms:us-east-1:123456789012:key/aaaa-bbbb-cccc";
        String redacted = AwsKmsSigningProvider.redactArn(arn);
        assertFalse(redacted.contains("123456789012"),
                "Account ID should be redacted: " + redacted);
        assertTrue(redacted.contains("<redacted>"),
                "Should contain <redacted>: " + redacted);
    }

    @Test
    void adcpAlgorithmFor_mapsCorrectly() throws SigningException {
        assertEquals(AdcpSignatureProfile.ALG_ED25519,
                AwsKmsSigningProvider.adcpAlgorithmFor(SigningAlgorithmSpec.ED25519_SHA_512));
        assertEquals(AdcpSignatureProfile.ALG_ECDSA_P256_SHA256,
                AwsKmsSigningProvider.adcpAlgorithmFor(SigningAlgorithmSpec.ECDSA_SHA_256));
        assertEquals("ecdsa-p384-sha384",
                AwsKmsSigningProvider.adcpAlgorithmFor(SigningAlgorithmSpec.ECDSA_SHA_384));
    }

    @Test
    void sign_noKeyArnForUse_throws() throws SigningException {
        GetPublicKeyResponse mockResponse = GetPublicKeyResponse.builder()
                .publicKey(SdkBytes.fromByteArray(new byte[32]))
                .signingAlgorithms(List.of(SigningAlgorithmSpec.ED25519_SHA_512))
                .build();
        when(kmsClient.getPublicKey(any(GetPublicKeyRequest.class))).thenReturn(mockResponse);

        AwsKmsSigningProvider provider = AwsKmsSigningProvider.builder()
                .keyArn(AdcpUse.REQUEST_SIGNING, REQUEST_KEY_ARN)
                .region("us-east-1")
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
        assertTrue(ex.getMessage().contains("No key ARN configured"),
                "Expected 'No key ARN configured' message, got: " + ex.getMessage());
    }

    private static byte[] generateEd25519PublicKeyBytes() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = kpg.generateKeyPair();
        return keyPair.getPublic().getEncoded();
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