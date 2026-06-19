package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.Signature;
import org.adcontextprotocol.adcp.signing.SigningContext;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.adcontextprotocol.adcp.signing.SigningInput;
import org.adcontextprotocol.adcp.signing.SigningProvider;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-process signing provider using JDK 21's built-in Ed25519 and ECDSA
 * (P-256/P-384) key support.
 *
 * <p>Takes a {@link PrivateKey} and key metadata (kid, algorithm, crv) in its
 * constructor. Supports:
 * <ul>
 *   <li>{@code EdDSA} (Ed25519)</li>
 *   <li>{@code ES256} (ECDSA P-256 with SHA-256)</li>
 *   <li>{@code ES384} (ECDSA P-384 with SHA-384)</li>
 * </ul>
 *
 * <p>Per D2 and the ROADMAP: <strong>No Bouncy Castle in core</strong> — JDK 21
 * has Ed25519 natively.
 */
public final class InProcessSigningProvider implements SigningProvider {

    private final PrivateKey privateKey;
    private final String kid;
    private final String alg;
    private final String crv;

    /**
     * Create an in-process signing provider.
     *
     * @param privateKey the JCA private key
     * @param kid        the key identifier
     * @param alg        the AdCP algorithm identifier (e.g. "ed25519", "ecdsa-p256-sha256")
     * @param crv        the curve name (e.g. "Ed25519", "P-256", "P-384"), or null for Ed25519
     */
    public InProcessSigningProvider(PrivateKey privateKey, String kid, String alg, String crv) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        this.kid = Objects.requireNonNull(kid, "kid");
        this.alg = Objects.requireNonNull(alg, "alg");
        this.crv = crv;
    }

    @Override
    public Signature sign(SigningContext context, SigningInput input) throws SigningException {
        String jcaAlgorithm = toJcaAlgorithm(alg, crv);

        String tag = AdcpSignatureProfile.tagForUse(context.use());
        List<String> coveredComponents = AdcpSignatureProfile.requiredComponentsForUse(context.use());

        long created = System.currentTimeMillis() / 1000;
        long expires = created + AdcpSignatureProfile.REPLAY_WINDOW_SECONDS;
        String nonce = generateNonce();

        String signatureInputValue = SignatureInputBuilder.create()
                .label(Signature.DEFAULT_LABEL)
                .coveredComponents(coveredComponents)
                .created(created)
                .expires(expires)
                .nonce(nonce)
                .keyid(kid)
                .alg(alg)
                .tag(tag)
                .build();

        Map<String, String> signingHeaders = new LinkedHashMap<>(input.headers());
        // Content-Digest is required for webhook signing even when the body is empty.
        // For request signing, it can be omitted for bodyless requests.
        boolean needsContentDigest = input.body() != null
                && (input.body().length > 0 || context.use() == AdcpUse.WEBHOOK_SIGNING);
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

        byte[] signatureBytes = doSign(jcaAlgorithm, signatureBase.getBytes(StandardCharsets.UTF_8));

        return new Signature(
                Signature.DEFAULT_LABEL,
                signatureInputValue,
                signatureBytes,
                alg,
                kid);
    }

    /**
     * Sign with explicit timestamps (for testing).
     */
    public Signature sign(SigningContext context, SigningInput input, long created, long expires, String nonce) throws SigningException {
        String jcaAlgorithm = toJcaAlgorithm(alg, crv);

        String tag = AdcpSignatureProfile.tagForUse(context.use());
        List<String> coveredComponents = AdcpSignatureProfile.requiredComponentsForUse(context.use());

        String signatureInputValue = SignatureInputBuilder.create()
                .label(Signature.DEFAULT_LABEL)
                .coveredComponents(coveredComponents)
                .created(created)
                .expires(expires)
                .nonce(nonce)
                .keyid(kid)
                .alg(alg)
                .tag(tag)
                .build();

        Map<String, String> signingHeaders = new LinkedHashMap<>(input.headers());
        // Content-Digest is required for webhook signing even when the body is empty.
        // For request signing, it can be omitted for bodyless requests.
        boolean needsContentDigest = input.body() != null
                && (input.body().length > 0 || context.use() == AdcpUse.WEBHOOK_SIGNING);
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

        byte[] signatureBytes = doSign(jcaAlgorithm, signatureBase.getBytes(StandardCharsets.UTF_8));

        return new Signature(
                Signature.DEFAULT_LABEL,
                signatureInputValue,
                signatureBytes,
                alg,
                kid);
    }

    private byte[] doSign(String jcaAlgorithm, byte[] data) throws SigningException {
        try {
            java.security.Signature signer = java.security.Signature.getInstance(jcaAlgorithm);
            signer.initSign(privateKey);
            signer.update(data);
            return signer.sign();
        } catch (Exception e) {
            throw new SigningException("Failed to sign: " + e.getMessage(), e);
        }
    }

    /**
     * Convert AdCP algorithm identifier to JCA algorithm name.
     */
    static String toJcaAlgorithm(String alg, String crv) throws SigningException {
        return switch (alg) {
            case AdcpSignatureProfile.ALG_ED25519 -> "Ed25519";
            case AdcpSignatureProfile.ALG_ECDSA_P256_SHA256 -> "SHA256withECDSAinP1363Format";
            case "ecdsa-p384-sha384" -> "SHA384withECDSAinP1363Format";
            default -> throw new SigningException("Unsupported signing algorithm: " + alg);
        };
    }

    private static String generateNonce() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}