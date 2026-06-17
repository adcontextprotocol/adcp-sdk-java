package org.adcontextprotocol.adcp.signing.gcp;

import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.AsymmetricSignResponse;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.PublicKey;
import com.google.protobuf.ByteString;
import org.adcontextprotocol.adcp.server.signing.AdcpSignatureProfile;
import org.adcontextprotocol.adcp.server.signing.ContentDigest;
import org.adcontextprotocol.adcp.server.signing.Rfc9421Canonicalizer;
import org.adcontextprotocol.adcp.server.signing.SignatureInputBuilder;
import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.Signature;
import org.adcontextprotocol.adcp.signing.SigningContext;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.adcontextprotocol.adcp.signing.SigningInput;
import org.adcontextprotocol.adcp.signing.SigningProvider;
import org.jspecify.annotations.Nullable;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class GcpKmsSigningProvider implements SigningProvider {

    private static final class KeyMetadata {
        final Map<AdcpUse, CryptoKeyVersionAlgorithm> algorithms;

        KeyMetadata(Map<AdcpUse, CryptoKeyVersionAlgorithm> algorithms) {
            this.algorithms = Map.copyOf(algorithms);
        }
    }

    private final Map<AdcpUse, String> keyVersionPaths;
    private final Map<AdcpUse, String> committedFingerprints;
    private final @Nullable String credentialsPath;
    private final @Nullable KeyManagementServiceClient externalClient;

    private volatile @Nullable KeyManagementServiceClient kmsClient;
    private final AtomicReference<@Nullable KeyMetadata> keyMetadata = new AtomicReference<>();
    private final AtomicReference<@Nullable Exception> initFailure = new AtomicReference<>();

    private GcpKmsSigningProvider(Builder builder) {
        if (builder.keyVersionPaths.isEmpty()) {
            throw new IllegalStateException("At least one key version path must be configured via keyVersionPath()");
        }
        this.keyVersionPaths = Map.copyOf(builder.keyVersionPaths);
        this.committedFingerprints = Map.copyOf(builder.committedFingerprints);
        this.credentialsPath = builder.credentialsPath;
        this.externalClient = builder.kmsClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Signature sign(SigningContext context, SigningInput input) throws SigningException {
        AdcpUse use = context.use();
        String keyVersionPath = keyVersionPaths.get(use);
        if (keyVersionPath == null) {
            throw new SigningException("No key version path configured for AdcpUse: " + use);
        }

        KeyMetadata metadata = ensureInitialized();

        CryptoKeyVersionAlgorithm kmsAlgEnum = metadata.algorithms.get(use);
        if (kmsAlgEnum == null) {
            throw new SigningException("No signing algorithm resolved for AdcpUse: " + use);
        }

        String alg = adcpAlgorithmFor(kmsAlgEnum);
        String tag = AdcpSignatureProfile.tagForUse(use);
        List<String> coveredComponents = AdcpSignatureProfile.requiredComponentsForUse(use);

        long created = System.currentTimeMillis() / 1000;
        long expires = created + AdcpSignatureProfile.REPLAY_WINDOW_SECONDS;
        String nonce = generateNonce();

        String signatureInputValue = SignatureInputBuilder.create()
                .label(Signature.DEFAULT_LABEL)
                .coveredComponents(coveredComponents)
                .created(created)
                .expires(expires)
                .nonce(nonce)
                .keyid(keyVersionPath)
                .alg(alg)
                .tag(tag)
                .build();

        Map<String, String> signingHeaders = new LinkedHashMap<>(input.headers());
        if (input.body() != null && input.body().length > 0) {
            String contentDigestValue = ContentDigest.sha256(input.body());
            signingHeaders.put("content-digest", contentDigestValue);
        }

        String signatureBase = Rfc9421Canonicalizer.canonicalize(
                input.method(),
                input.targetUri(),
                signingHeaders,
                coveredComponents,
                signatureInputValue);

        byte[] messageBytes = signatureBase.getBytes(StandardCharsets.UTF_8);

        byte[] kmsSignature = callKmsSign(keyVersionPath, messageBytes);

        byte[] signatureBytes;
        if (isEd25519(kmsAlgEnum)) {
            signatureBytes = kmsSignature;
        } else {
            int fieldSize = isP384(kmsAlgEnum) ? 48 : 32;
            signatureBytes = ecdsaDerToRaw(kmsSignature, fieldSize);
        }

        return new Signature(
                Signature.DEFAULT_LABEL,
                signatureInputValue,
                signatureBytes,
                alg,
                keyVersionPath);
    }

    private KeyMetadata ensureInitialized() throws SigningException {
        KeyMetadata metadata = keyMetadata.get();
        if (metadata != null) return metadata;

        Exception failure = initFailure.get();
        if (failure != null) {
            throw new SigningException("GCP KMS provider initialization failed: " + failure.getMessage(), failure);
        }

        synchronized (this) {
            metadata = keyMetadata.get();
            if (metadata != null) return metadata;

            failure = initFailure.get();
            if (failure != null) {
                throw new SigningException("GCP KMS provider initialization failed: " + failure.getMessage(), failure);
            }

            try {
                KeyManagementServiceClient client = getClient();
                Map<AdcpUse, CryptoKeyVersionAlgorithm> algorithms = new EnumMap<>(AdcpUse.class);

                for (Map.Entry<AdcpUse, String> entry : keyVersionPaths.entrySet()) {
                    AdcpUse use = entry.getKey();
                    String keyVersionPath = entry.getValue();

                    PublicKey pubKey = client.getPublicKey(keyVersionPath);
                    CryptoKeyVersionAlgorithm kmsAlg = pubKey.getAlgorithm();
                    algorithms.put(use, kmsAlg);

                    String expectedFingerprint = committedFingerprints.get(use);
                    if (expectedFingerprint != null) {
                        String pem = pubKey.getPem();
                        byte[] spkiDer = extractSpkiDer(pem);
                        String actualFingerprint = sha256Hex(spkiDer);
                        if (!expectedFingerprint.equals(actualFingerprint)) {
                            throw new SigningException(
                                    "Public key fingerprint mismatch for " + redactKeyVersionPath(keyVersionPath)
                                            + ": expected " + expectedFingerprint
                                            + " but got " + actualFingerprint
                                            + ". If the key was rotated, update the committed fingerprint.");
                        }
                    }
                }

                metadata = new KeyMetadata(algorithms);
                keyMetadata.set(metadata);
                return metadata;
            } catch (SigningException e) {
                initFailure.set(e);
                throw e;
            } catch (Exception e) {
                SigningException se = new SigningException("Failed to initialize GCP KMS: " + e.getMessage(), e);
                initFailure.set(se);
                throw se;
            }
        }
    }

    KeyManagementServiceClient getClient() throws SigningException {
        if (externalClient != null) return externalClient;
        if (kmsClient == null) {
            synchronized (this) {
                if (kmsClient == null) {
                    try {
                        if (credentialsPath != null) {
                            com.google.auth.oauth2.ServiceAccountCredentials credentials =
                                    com.google.auth.oauth2.ServiceAccountCredentials.fromStream(
                                            new FileInputStream(credentialsPath));
                            kmsClient = KeyManagementServiceClient.create(
                                    com.google.cloud.kms.v1.KeyManagementServiceSettings.newBuilder()
                                            .setCredentialsProvider(com.google.api.gax.core.FixedCredentialsProvider.create(credentials))
                                            .build());
                        } else {
                            kmsClient = KeyManagementServiceClient.create();
                        }
                    } catch (Exception e) {
                        throw new SigningException("Failed to create GCP KMS client: " + e.getMessage(), e);
                    }
                }
            }
        }
        return kmsClient;
    }

    void initialize() throws SigningException {
        ensureInitialized();
    }

    boolean isInitialized() {
        return keyMetadata.get() != null;
    }

    Map<AdcpUse, CryptoKeyVersionAlgorithm> getAlgorithms() {
        KeyMetadata metadata = keyMetadata.get();
        return metadata != null ? metadata.algorithms : Map.of();
    }

    private byte[] callKmsSign(String keyVersionPath, byte[] message) throws SigningException {
        try {
            AsymmetricSignRequest request = AsymmetricSignRequest.newBuilder()
                    .setName(keyVersionPath)
                    .setData(ByteString.copyFrom(message))
                    .build();

            AsymmetricSignResponse response = getClient().asymmetricSign(request);

            if (response.getSignature().isEmpty()) {
                throw new SigningException("GCP KMS asymmetricSign returned no signature for " + redactKeyVersionPath(keyVersionPath));
            }
            return response.getSignature().toByteArray();
        } catch (SigningException e) {
            throw e;
        } catch (Exception e) {
            throw new SigningException("GCP KMS asymmetricSign failed for " + redactKeyVersionPath(keyVersionPath) + ": " + e.getMessage(), e);
        }
    }

    static String adcpAlgorithmFor(CryptoKeyVersionAlgorithm gcpAlg) throws SigningException {
        return switch (gcpAlg) {
            case EC_SIGN_ED25519 -> AdcpSignatureProfile.ALG_ED25519;
            case EC_SIGN_P256_SHA256 -> AdcpSignatureProfile.ALG_ECDSA_P256_SHA256;
            case EC_SIGN_P384_SHA384 -> "ecdsa-p384-sha384";
            default -> throw new SigningException("Unsupported GCP KMS signing algorithm: " + gcpAlg);
        };
    }

    private static boolean isEd25519(CryptoKeyVersionAlgorithm alg) {
        return alg == CryptoKeyVersionAlgorithm.EC_SIGN_ED25519;
    }

    private static boolean isP384(CryptoKeyVersionAlgorithm alg) {
        return alg == CryptoKeyVersionAlgorithm.EC_SIGN_P384_SHA384;
    }

    static byte[] ecdsaDerToRaw(byte[] derSignature, int fieldSize) {
        int offset = 0;
        if (derSignature[offset] != 0x30) {
            throw new IllegalArgumentException("Invalid DER signature: missing SEQUENCE tag");
        }
        offset++;

        int seqLen = derSignature[offset] & 0xFF;
        offset++;
        if (seqLen == 0x80) {
            throw new IllegalArgumentException("Indefinite-length DER not supported");
        }

        if (derSignature[offset] != 0x02) {
            throw new IllegalArgumentException("Invalid DER signature: missing INTEGER tag for r");
        }
        offset++;

        int rLen = derSignature[offset] & 0xFF;
        offset++;

        int rPad = (rLen > 1 && derSignature[offset] == 0x00) ? 1 : 0;
        byte[] r = new byte[fieldSize];
        int rSrcLen = rLen - rPad;
        if (rSrcLen > fieldSize) {
            System.arraycopy(derSignature, offset + rPad + (rSrcLen - fieldSize), r, 0, fieldSize);
        } else {
            System.arraycopy(derSignature, offset + rPad, r, fieldSize - rSrcLen, rSrcLen);
        }
        offset += rLen;

        if (derSignature[offset] != 0x02) {
            throw new IllegalArgumentException("Invalid DER signature: missing INTEGER tag for s");
        }
        offset++;

        int sLen = derSignature[offset] & 0xFF;
        offset++;

        int sPad = (sLen > 1 && derSignature[offset] == 0x00) ? 1 : 0;
        byte[] s = new byte[fieldSize];
        int sSrcLen = sLen - sPad;
        if (sSrcLen > fieldSize) {
            System.arraycopy(derSignature, offset + sPad + (sSrcLen - fieldSize), s, 0, fieldSize);
        } else {
            System.arraycopy(derSignature, offset + sPad, s, fieldSize - sSrcLen, sSrcLen);
        }

        byte[] raw = new byte[fieldSize * 2];
        System.arraycopy(r, 0, raw, 0, fieldSize);
        System.arraycopy(s, 0, raw, fieldSize, fieldSize);
        return raw;
    }

    static byte[] extractSpkiDer(String pem) throws SigningException {
        String stripped = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getMimeDecoder().decode(stripped);
        } catch (IllegalArgumentException e) {
            throw new SigningException("Failed to decode PEM public key: " + e.getMessage(), e);
        }
    }

    private static String generateNonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String redactKeyVersionPath(String keyVersionPath) {
        return keyVersionPath.replaceAll("projects/[^/]+", "projects/<redacted>");
    }

    public static final class Builder {
        private final EnumMap<AdcpUse, String> keyVersionPaths = new EnumMap<>(AdcpUse.class);
        private final EnumMap<AdcpUse, String> committedFingerprints = new EnumMap<>(AdcpUse.class);
        private @Nullable String credentialsPath;
        private @Nullable KeyManagementServiceClient kmsClient;

        private Builder() {}

        public Builder keyVersionPath(AdcpUse use, String keyVersionPath) {
            Objects.requireNonNull(use, "use");
            Objects.requireNonNull(keyVersionPath, "keyVersionPath");
            if (keyVersionPath.isBlank()) {
                throw new IllegalArgumentException("keyVersionPath must not be blank");
            }
            keyVersionPaths.put(use, keyVersionPath);
            return this;
        }

        public Builder credentialsPath(String path) {
            Objects.requireNonNull(path, "credentialsPath");
            if (path.isBlank()) {
                throw new IllegalArgumentException("credentialsPath must not be blank");
            }
            this.credentialsPath = path;
            return this;
        }

        public Builder committedPublicKeyFingerprint(AdcpUse use, String fingerprint) {
            Objects.requireNonNull(use, "use");
            Objects.requireNonNull(fingerprint, "fingerprint");
            committedFingerprints.put(use, fingerprint);
            return this;
        }

        Builder kmsClient(KeyManagementServiceClient client) {
            this.kmsClient = client;
            return this;
        }

        public GcpKmsSigningProvider build() {
            return new GcpKmsSigningProvider(this);
        }
    }
}