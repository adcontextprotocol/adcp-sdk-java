package org.adcontextprotocol.adcp.signing.bc;

import org.adcontextprotocol.adcp.signing.Signature;
import org.adcontextprotocol.adcp.signing.SigningContext;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.adcontextprotocol.adcp.signing.SigningInput;
import org.adcontextprotocol.adcp.signing.SigningProvider;

import java.util.Objects;

/**
 * Bouncy Castle FIPS signing provider for AdCP — <strong>stub</strong>.
 *
 * <p>This is a module skeleton for the {@code adcp-signing-bouncycastle} artifact.
 * The real implementation will use {@code org.bouncycastle:bc-fips} to provide
 * signing and verification in FIPS 140-validated environments.
 *
 * <p><strong>This provider is optional.</strong> The core AdCP signing path uses
 * JDK 21's built-in Ed25519 and ECDSA support ({@code InProcessSigningProvider}
 * in {@code adcp-server}). This module exists for deployments that require
 * FIPS 140 compliance where the JDK's native crypto is not sufficient.
 *
 * <p>Constructor accepts FIPS provider configuration. The Bouncy Castle
 * provider is lazy-initialised — it is not registered until the first
 * {@link #sign} call.
 *
 * @see SigningProvider
 */
public final class BouncyCastleSigningProvider implements SigningProvider {

    private final String fipsProviderName;

    /**
     * Create a Bouncy Castle FIPS signing provider stub.
     *
     * @param fipsProviderName the JCA provider name for Bouncy Castle FIPS
     *                         (e.g. "BCFIPS")
     */
    public BouncyCastleSigningProvider(String fipsProviderName) {
        this.fipsProviderName = Objects.requireNonNull(fipsProviderName, "fipsProviderName");
    }

    @Override
    public Signature sign(SigningContext context, SigningInput input) throws SigningException {
        throw new UnsupportedOperationException(
                "Bouncy Castle FIPS signing not yet implemented — track 4 v0.2");
    }
}