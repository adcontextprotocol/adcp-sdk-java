package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SignedInput;
import org.adcontextprotocol.adcp.signing.VerificationException;
import org.adcontextprotocol.adcp.signing.VerificationKey;

import java.security.PublicKey;
import java.util.Map;

/**
 * In-process verification provider using JDK 21's built-in Ed25519 and ECDSA
 * key support. Delegates to {@link Rfc9421Verifier} for the core verification
 * logic and AdCP profile validation.
 *
 * <p>This provider consolidates the full verification path:
 * <ol>
 *   <li>Parse the {@code Signature-Input} header from request headers</li>
 *   <li>Validate all AdCP profile requirements (tag, created/expires window,
 *       nonce, keyid, alg, required covered components)</li>
 *   <li>Build the signature base using {@link Rfc9421Canonicalizer}</li>
 *   <li>Verify the signature bytes against the base using the public key</li>
 *   <li>Return {@link VerificationResult.Valid} or {@link VerificationResult.Invalid}
 *       with the appropriate {@code webhook_signature_*} error code</li>
 * </ol>
 */
public final class InProcessVerificationProvider {

    private InProcessVerificationProvider() {}

    /**
     * Verify an inbound webhook signature.
     *
     * @param input        the signed input from the wire
     * @param key          the verification key
     * @param expectedUse  the expected AdCP use (WEBHOOK_SIGNING or REQUEST_SIGNING)
     * @param referenceNow Unix seconds representing "now" for window validation
     * @return a VerificationResult
     */
    public static VerificationResult verify(SignedInput input, VerificationKey key, AdcpUse expectedUse, long referenceNow) {
        return Rfc9421Verifier.verify(input, key, expectedUse, referenceNow);
    }

    /**
     * Verify an inbound webhook signature using "now" as the reference timestamp.
     */
    public static VerificationResult verify(SignedInput input, VerificationKey key, AdcpUse expectedUse) {
        return Rfc9421Verifier.verify(input, key, expectedUse, System.currentTimeMillis() / 1000);
    }
}