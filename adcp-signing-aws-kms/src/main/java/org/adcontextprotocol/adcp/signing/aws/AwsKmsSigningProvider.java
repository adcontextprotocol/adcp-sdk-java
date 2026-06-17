package org.adcontextprotocol.adcp.signing.aws;

import org.adcontextprotocol.adcp.signing.Signature;
import org.adcontextprotocol.adcp.signing.SigningContext;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.adcontextprotocol.adcp.signing.SigningInput;
import org.adcontextprotocol.adcp.signing.SigningProvider;

import java.util.Map;
import java.util.Objects;

/**
 * AWS KMS signing provider for AdCP — <strong>stub</strong>.
 *
 * <p>This is a module skeleton for the {@code adcp-signing-aws-kms} artifact.
 * The real implementation will use {@code software.amazon.awssdk:kms} to sign
 * and verify AdCP webhook signatures against keys stored in AWS KMS.
 *
 * <p>Constructor accepts AWS KMS client configuration (region, key ARN mapping).
 * The KMS client is lazy-initialised — it is not touched until the first
 * {@link #sign} call, so the module can be on the classpath without an active
 * AWS connection.
 *
 * <p>Pre-deploy key-probe checks are handled by {@code adcp-cli}, not by this
 * provider at boot time.
 *
 * @see SigningProvider
 */
public final class AwsKmsSigningProvider implements SigningProvider {

    private final String region;
    private final Map<String, String> keyArnMapping;

    /**
     * Create an AWS KMS signing provider stub.
     *
     * @param region        AWS region (e.g. "us-east-1")
     * @param keyArnMapping mapping from AdCP key identifiers to KMS key ARNs
     */
    public AwsKmsSigningProvider(String region, Map<String, String> keyArnMapping) {
        this.region = Objects.requireNonNull(region, "region");
        this.keyArnMapping = Map.copyOf(Objects.requireNonNull(keyArnMapping, "keyArnMapping"));
    }

    @Override
    public Signature sign(SigningContext context, SigningInput input) throws SigningException {
        throw new UnsupportedOperationException(
                "AWS KMS signing not yet implemented — track 4 v0.2");
    }
}