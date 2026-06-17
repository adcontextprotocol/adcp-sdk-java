package org.adcontextprotocol.adcp.signing;

/**
 * Inbound verification SPI. Given a {@link VerificationInput} (which carries
 * the inbound {@code kid}), resolves to either a {@link VerificationKeyLookup.Found}
 * with key material and optional tenant/principal metadata, or
 * {@link VerificationKeyLookup.Missing} if no key matches.
 *
 * <p>Per the signing-context spec: verification starts from the inbound {@code kid}.
 * Tenant context is derived <em>after</em> key lookup, never before.
 */
@FunctionalInterface
public interface VerificationKeyResolver {
    VerificationKeyLookup resolve(VerificationInput input);
}