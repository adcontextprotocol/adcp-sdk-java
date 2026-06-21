package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SigningContext;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.adcontextprotocol.adcp.signing.SigningInput;
import org.adcontextprotocol.adcp.signing.SigningProvider;
import org.adcontextprotocol.adcp.signing.Signature;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 9421 signing implementation. Takes a {@link SigningContext} +
 * {@link SigningInput} + key material and produces the Signature-Input header
 * value, the Signature header value, and the Content-Digest header value.
 *
 * <p>Uses {@link Rfc9421Canonicalizer} internally to build the signature base.
 */
public final class Rfc9421Signer {

    private Rfc9421Signer() {}

    /**
     * Sign the given input and return the complete set of signature headers.
     *
     * @param context    signing context (determines purpose, tag, required components)
     * @param input      the signing input (method, URI, headers, body)
     * @param keyId      the key identifier for the Signature-Input header
     * @param alg        the algorithm identifier (e.g. "ed25519", "ecdsa-p256-sha256")
     * @param privateKey the private key bytes (PKCS8 for Ed25519/ECDSA)
     * @param created   Unix seconds timestamp for signature creation
     * @param expires    Unix seconds timestamp for signature expiration
     * @param nonce      the nonce value for replay protection
     * @return a SignedOutput containing the three header values
     * @throws SigningException if signing fails
     */
    public static SignedOutput sign(
            SigningContext context,
            SigningInput input,
            String keyId,
            String alg,
            byte[] privateKey,
            long created,
            long expires,
            String nonce) throws SigningException {

        AdcpUse use = context.use();
        String tag = AdcpSignatureProfile.tagForUse(use);
        List<String> coveredComponents = AdcpSignatureProfile.requiredComponentsForUse(use);

        // Build Signature-Input header
        String signatureInputValue = SignatureInputBuilder.create()
                .label("sig1")
                .coveredComponents(coveredComponents)
                .created(created)
                .expires(expires)
                .nonce(nonce)
                .keyid(keyId)
                .alg(alg)
                .tag(tag)
                .build();

        // Compute Content-Digest if body is present.
        // For webhook signing, content-digest is a required covered component,
        // so emit it even for empty bodies (digest of byte[0]). For request
        // signing, it can be omitted for bodyless requests.
        String contentDigestValue = null;
        Map<String, String> signingHeaders = new LinkedHashMap<>(input.headers());
        boolean needsContentDigest = input.body() != null
                && (input.body().length > 0 || use == AdcpUse.WEBHOOK_SIGNING);
        if (needsContentDigest) {
            contentDigestValue = ContentDigest.sha256(input.body());
            signingHeaders.put("content-digest", contentDigestValue);
        }

        // Build signature base
        String signatureBase = Rfc9421Canonicalizer.canonicalize(
                input.method(),
                input.targetUri(),
                signingHeaders,
                coveredComponents,
                signatureInputValue);

        // Sign the signature base
        byte[] signatureBytes = doSign(alg, privateKey, signatureBase.getBytes(StandardCharsets.UTF_8));
        String signatureEncoded = ContentDigest.base64UrlNoPadding(signatureBytes);

        return new SignedOutput(
                signatureInputValue,
                "sig1=:" + signatureEncoded + ":",
                contentDigestValue
        );
    }

    /**
     * Perform the cryptographic signature.
     */
    private static byte[] doSign(String alg, byte[] privateKeyBytes, byte[] data) throws SigningException {
        String jcaAlgorithm;
        if (AdcpSignatureProfile.ALG_ED25519.equals(alg)) {
            jcaAlgorithm = "Ed25519";
        } else if (AdcpSignatureProfile.ALG_ECDSA_P256_SHA256.equals(alg)) {
            jcaAlgorithm = "SHA256withECDSAinP1363Format";
        } else if (AdcpSignatureProfile.ALG_ECDSA_P384_SHA384.equals(alg)) {
            jcaAlgorithm = "SHA384withECDSAinP1363Format";
        } else {
            throw new SigningException("Unsupported signing algorithm: " + alg);
        }

        try {
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance(
                    jcaAlgorithm.equals("Ed25519") ? "Ed25519" : "EC");
            PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
            java.security.Signature signer = java.security.Signature.getInstance(jcaAlgorithm);
            signer.initSign(privateKey);
            signer.update(data);
            return signer.sign();
        } catch (Exception e) {
            throw new SigningException("Failed to sign: " + e.getMessage(), e);
        }
    }

    /**
     * Output of the signing operation: the three headers that need to be sent.
     *
     * @param signatureInputValue the Signature-Input header value
     * @param signatureValue      the Signature header value
     * @param contentDigestValue  the Content-Digest header value, or null if no body
     */
    public record SignedOutput(
            String signatureInputValue,
            String signatureValue,
            String contentDigestValue
    ) {}
}