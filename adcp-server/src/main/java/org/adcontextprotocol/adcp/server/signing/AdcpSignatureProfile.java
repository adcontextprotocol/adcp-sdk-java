package org.adcontextprotocol.adcp.server.signing;

import java.util.List;
import java.util.Set;

/**
 * Constants and validation for the AdCP-specific RFC 9421 profile.
 *
 * <p>Defines required covered components per purpose (webhook vs request),
 * required signature parameters, allowed algorithms, tag values,
 * and the replay window.
 */
public final class AdcpSignatureProfile {

    private AdcpSignatureProfile() {}

    public static final String TAG_WEBHOOK_SIGNING = "adcp/webhook-signing/v1";
    public static final String TAG_REQUEST_SIGNING = "adcp/request-signing/v1";

    public static final String ALG_ED25519 = "ed25519";
    public static final String ALG_ECDSA_P256_SHA256 = "ecdsa-p256-sha256";
    public static final String ALG_ECDSA_P384_SHA384 = "ecdsa-p384-sha384";

    public static final Set<String> ALLOWED_ALGORITHMS = Set.of(
            ALG_ED25519, ALG_ECDSA_P256_SHA256, ALG_ECDSA_P384_SHA384);

    public static final long REPLAY_WINDOW_SECONDS = 300;

    /**
     * Required covered components for webhook signing:
     * {@code @method}, {@code @target-uri}, {@code @authority},
     * {@code content-type}, {@code content-digest}.
     */
    public static final List<String> WEBHOOK_REQUIRED_COMPONENTS = List.of(
            "@method", "@target-uri", "@authority", "content-type", "content-digest"
    );

    /**
     * Required covered components for request signing:
     * {@code @method}, {@code @target-uri}, {@code @authority},
     * {@code content-type}. Content-digest is required only when
     * the request has a body and the verifier capability requires it.
     */
    public static final List<String> REQUEST_REQUIRED_COMPONENTS = List.of(
            "@method", "@target-uri", "@authority", "content-type"
    );

    /**
     * Required signature parameters for both purposes:
     * {@code created}, {@code expires}, {@code nonce}, {@code keyid}, {@code alg}.
     */
    public static final Set<String> REQUIRED_SIGNATURE_PARAMS = Set.of(
            "created", "expires", "nonce", "keyid", "alg"
    );

    /**
     * Returns the tag value for the given AdCP use.
     *
     * @param adcpUse the AdCP use
     * @return the tag string
     * @throws IllegalArgumentException if the use is not recognized
     */
    public static String tagForUse(org.adcontextprotocol.adcp.signing.AdcpUse adcpUse) {
        return switch (adcpUse) {
            case WEBHOOK_SIGNING -> TAG_WEBHOOK_SIGNING;
            case REQUEST_SIGNING -> TAG_REQUEST_SIGNING;
        };
    }

    /**
     * Returns the required covered components for the given AdCP use.
     *
     * @param adcpUse the AdCP use
     * @return the list of required component identifiers
     */
    public static List<String> requiredComponentsForUse(org.adcontextprotocol.adcp.signing.AdcpUse adcpUse) {
        return switch (adcpUse) {
            case WEBHOOK_SIGNING -> WEBHOOK_REQUIRED_COMPONENTS;
            case REQUEST_SIGNING -> REQUEST_REQUIRED_COMPONENTS;
        };
    }

    /**
     * Check if an algorithm is allowed by the AdCP profile.
     *
     * @param alg the algorithm identifier from the Signature-Input
     * @return true if the algorithm is in the allowlist
     */
    public static boolean isAlgorithmAllowed(String alg) {
        return ALLOWED_ALGORITHMS.contains(alg);
    }

    /**
     * Validate that all required covered components are present for the given use.
     *
     * @param adcpUse the AdCP use
     * @param coveredComponents the covered components from the Signature-Input
     * @return null if valid, or the error code string if validation fails
     */
    public static String validateRequiredComponents(
            org.adcontextprotocol.adcp.signing.AdcpUse adcpUse,
            List<String> coveredComponents) {
        List<String> required = requiredComponentsForUse(adcpUse);
        for (String component : required) {
            if (!coveredComponents.contains(component)) {
                return adcpUse == org.adcontextprotocol.adcp.signing.AdcpUse.WEBHOOK_SIGNING
                        ? "webhook_signature_components_incomplete"
                        : "request_signature_components_incomplete";
            }
        }
        if (adcpUse == org.adcontextprotocol.adcp.signing.AdcpUse.WEBHOOK_SIGNING) {
            if (!coveredComponents.contains("content-digest")) {
                return "webhook_signature_components_incomplete";
            }
        }
        return null;
    }

    /**
     * Validate the signature parameters include all required fields.
     *
     * @param params the signature parameters map (name to value)
     * @return null if valid, or the error code string if validation fails
     */
    public static String validateRequiredParams(java.util.Map<String, String> params) {
        for (String required : REQUIRED_SIGNATURE_PARAMS) {
            if (!params.containsKey(required)) {
                return "webhook_signature_params_incomplete";
            }
        }
        return null;
    }
}