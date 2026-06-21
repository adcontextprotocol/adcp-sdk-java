package org.adcontextprotocol.adcp.signing.aws;

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
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SignResponse;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class AwsKmsSigningProvider implements SigningProvider {

    private static final class KeyMetadata {
        final Map<AdcpUse, SigningAlgorithmSpec> algorithms;

        KeyMetadata(Map<AdcpUse, SigningAlgorithmSpec> algorithms) {
            this.algorithms = Map.copyOf(algorithms);
        }
    }

    private final Map<AdcpUse, String> keyArns;
    private final Map<AdcpUse, String> committedFingerprints;
    private final String region;
    private final @Nullable KmsClient externalClient;

    private volatile @Nullable KmsClient kmsClient;
    private final AtomicReference<@Nullable KeyMetadata> keyMetadata = new AtomicReference<>();
    private final AtomicReference<@Nullable Exception> initFailure = new AtomicReference<>();

    private AwsKmsSigningProvider(Builder builder) {
        if (builder.keyArns.isEmpty()) {
            throw new IllegalStateException("At least one key ARN must be configured via keyArn()");
        }
        this.keyArns = Map.copyOf(builder.keyArns);
        this.committedFingerprints = Map.copyOf(builder.committedFingerprints);
        this.region = Objects.requireNonNull(builder.region, "region");
        this.externalClient = builder.kmsClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Signature sign(SigningContext context, SigningInput input) throws SigningException {
        AdcpUse use = context.use();
        String keyArn = keyArns.get(use);
        if (keyArn == null) {
            throw new SigningException("No key ARN configured for AdcpUse: " + use);
        }

        KeyMetadata metadata = ensureInitialized();

        SigningAlgorithmSpec kmsAlg = metadata.algorithms.get(use);
        if (kmsAlg == null) {
            throw new SigningException("No signing algorithm resolved for AdcpUse: " + use);
        }

        String alg = adcpAlgorithmFor(kmsAlg);
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
                .keyid(keyArn)
                .alg(alg)
                .tag(tag)
                .build();

        Map<String, String> signingHeaders = new LinkedHashMap<>(input.headers());
        // Content-Digest is required for webhook signing even when the body is empty.
        // For request signing, it can be omitted for bodyless requests.
        boolean needsContentDigest = input.body() != null
                && (input.body().length > 0 || use == AdcpUse.WEBHOOK_SIGNING);
        if (needsContentDigest) {
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

        byte[] kmsSignature = callKmsSign(keyArn, kmsAlg, messageBytes);

        byte[] signatureBytes;
        if (isEd25519(kmsAlg)) {
            signatureBytes = kmsSignature;
        } else {
            int fieldSize = (kmsAlg == SigningAlgorithmSpec.ECDSA_SHA_384) ? 48 : 32;
            signatureBytes = ecdsaDerToRaw(kmsSignature, fieldSize);
        }

        return new Signature(
                Signature.DEFAULT_LABEL,
                signatureInputValue,
                signatureBytes,
                alg,
                keyArn);
    }

    private KeyMetadata ensureInitialized() throws SigningException {
        KeyMetadata metadata = keyMetadata.get();
        if (metadata != null) return metadata;

        Exception failure = initFailure.get();
        if (failure != null) {
            throw new SigningException("AWS KMS provider initialization failed: " + failure.getMessage(), failure);
        }

        synchronized (this) {
            metadata = keyMetadata.get();
            if (metadata != null) return metadata;

            failure = initFailure.get();
            if (failure != null) {
                throw new SigningException("AWS KMS provider initialization failed: " + failure.getMessage(), failure);
            }

            try {
                KmsClient client = getClient();
                Map<AdcpUse, SigningAlgorithmSpec> algorithms = new EnumMap<>(AdcpUse.class);

                for (Map.Entry<AdcpUse, String> entry : keyArns.entrySet()) {
                    AdcpUse use = entry.getKey();
                    String keyArn = entry.getValue();

                    GetPublicKeyResponse pubKeyResp = client.getPublicKey(GetPublicKeyRequest.builder()
                            .keyId(keyArn)
                            .build());

                    SigningAlgorithmSpec alg = resolveSigningAlgorithm(pubKeyResp.signingAlgorithms());
                    algorithms.put(use, alg);

                    String expectedFingerprint = committedFingerprints.get(use);
                    if (expectedFingerprint != null) {
                        byte[] spkiDer = pubKeyResp.publicKey().asByteArray();
                        String actualFingerprint = sha256Hex(spkiDer);
                        if (!expectedFingerprint.equals(actualFingerprint)) {
                            throw new SigningException(
                                    "Public key fingerprint mismatch for " + redactArn(keyArn)
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
            } catch (KmsException e) {
                SigningException se = new SigningException("Failed to initialize AWS KMS: " + e.getMessage(), e);
                initFailure.set(se);
                throw se;
            }
        }
    }

    KmsClient getClient() {
        if (externalClient != null) return externalClient;
        if (kmsClient == null) {
            synchronized (this) {
                if (kmsClient == null) {
                    kmsClient = KmsClient.builder()
                            .region(Region.of(region))
                            .build();
                }
            }
        }
        return kmsClient;
    }

    public void initialize() throws SigningException {
        ensureInitialized();
    }

    public boolean isInitialized() {
        return keyMetadata.get() != null;
    }

    public Map<AdcpUse, SigningAlgorithmSpec> getAlgorithms() {
        KeyMetadata metadata = keyMetadata.get();
        return metadata != null ? metadata.algorithms : Map.of();
    }

    private byte[] callKmsSign(String keyArn, SigningAlgorithmSpec alg, byte[] message) throws SigningException {
        try {
            SignResponse response = getClient().sign(SignRequest.builder()
                    .keyId(keyArn)
                    .signingAlgorithm(alg)
                    .message(SdkBytes.fromByteArray(message))
                    .build());

            if (response.signature() == null) {
                throw new SigningException("KMS Sign returned no signature for " + redactArn(keyArn));
            }
            return response.signature().asByteArray();
        } catch (KmsException e) {
            throw new SigningException("KMS Sign failed for " + redactArn(keyArn) + ": " + e.getMessage(), e);
        }
    }

    static String adcpAlgorithmFor(SigningAlgorithmSpec kmsAlg) throws SigningException {
        return switch (kmsAlg) {
            case ED25519_SHA_512 -> AdcpSignatureProfile.ALG_ED25519;
            case ECDSA_SHA_256 -> AdcpSignatureProfile.ALG_ECDSA_P256_SHA256;
            case ECDSA_SHA_384 -> AdcpSignatureProfile.ALG_ECDSA_P384_SHA384;
            default -> throw new SigningException("Unsupported KMS signing algorithm: " + kmsAlg);
        };
    }

    private static boolean isEd25519(SigningAlgorithmSpec alg) {
        return alg == SigningAlgorithmSpec.ED25519_SHA_512;
    }

    private static SigningAlgorithmSpec resolveSigningAlgorithm(List<SigningAlgorithmSpec> algs) throws SigningException {
        if (algs.contains(SigningAlgorithmSpec.ECDSA_SHA_256)) {
            return SigningAlgorithmSpec.ECDSA_SHA_256;
        }
        if (algs.contains(SigningAlgorithmSpec.ECDSA_SHA_384)) {
            return SigningAlgorithmSpec.ECDSA_SHA_384;
        }
        if (algs.contains(SigningAlgorithmSpec.ED25519_SHA_512)) {
            return SigningAlgorithmSpec.ED25519_SHA_512;
        }
        throw new SigningException("No supported signing algorithm found. Supported: ED25519_SHA_512, ECDSA_SHA_256, ECDSA_SHA_384. Got: " + algs);
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

    private static String generateNonce() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    static String redactArn(String arn) {
        return arn.replaceAll("(arn:aws:kms:[^:]*:)[^:]+(:)", "$1<redacted>$2");
    }

    public static final class Builder {
        private final EnumMap<AdcpUse, String> keyArns = new EnumMap<>(AdcpUse.class);
        private final EnumMap<AdcpUse, String> committedFingerprints = new EnumMap<>(AdcpUse.class);
        private String region = "us-east-1";
        private @Nullable KmsClient kmsClient;

        private Builder() {}

        public Builder keyArn(AdcpUse use, String keyArn) {
            Objects.requireNonNull(use, "use");
            Objects.requireNonNull(keyArn, "keyArn");
            if (keyArn.isBlank()) {
                throw new IllegalArgumentException("keyArn must not be blank");
            }
            keyArns.put(use, keyArn);
            return this;
        }

        public Builder region(String region) {
            Objects.requireNonNull(region, "region");
            if (region.isBlank()) {
                throw new IllegalArgumentException("region must not be blank");
            }
            this.region = region;
            return this;
        }

        public Builder committedPublicKeyFingerprint(AdcpUse use, String fingerprint) {
            Objects.requireNonNull(use, "use");
            Objects.requireNonNull(fingerprint, "fingerprint");
            committedFingerprints.put(use, fingerprint);
            return this;
        }

        Builder kmsClient(KmsClient client) {
            this.kmsClient = client;
            return this;
        }

        public AwsKmsSigningProvider build() {
            return new AwsKmsSigningProvider(this);
        }
    }
}