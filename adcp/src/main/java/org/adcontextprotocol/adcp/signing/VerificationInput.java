package org.adcontextprotocol.adcp.signing;

import java.util.Objects;

/**
 * Input for inbound signature verification. Carries the expected purpose,
 * the inbound {@code kid}, and the raw signed data.
 *
 * @param expectedUse the AdCP use the key must satisfy
 * @param kid         the key identifier from the inbound signature
 * @param input       the signed input to verify
 */
public record VerificationInput(AdcpUse expectedUse, String kid, SignedInput input) {

    public VerificationInput {
        Objects.requireNonNull(expectedUse, "expectedUse");
        Objects.requireNonNull(kid, "kid");
        Objects.requireNonNull(input, "input");
    }
}