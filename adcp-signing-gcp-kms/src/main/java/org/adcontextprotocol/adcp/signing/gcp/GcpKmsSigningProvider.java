package org.adcontextprotocol.adcp.signing.gcp;

import org.adcontextprotocol.adcp.signing.Signature;
import org.adcontextprotocol.adcp.signing.SigningContext;
import org.adcontextprotocol.adcp.signing.SigningException;
import org.adcontextprotocol.adcp.signing.SigningInput;
import org.adcontextprotocol.adcp.signing.SigningProvider;

import java.util.Map;
import java.util.Objects;

/**
 * Google Cloud KMS signing provider for AdCP — <strong>stub</strong>.
 *
 * <p>This is a module skeleton for the {@code adcp-signing-gcp-kms} artifact.
 * The real implementation will use {@code com.google.cloud:google-cloud-kms}
 * to sign and verify AdCP webhook signatures against keys stored in GCP KMS.
 *
 * <p>Constructor accepts GCP KMS client configuration (project ID, location,
 * key ring mapping). The KMS client is lazy-initialised — it is not touched
 * until the first {@link #sign} call, so the module can be on the classpath
 * without an active GCP connection.
 *
 * @see SigningProvider
 */
public final class GcpKmsSigningProvider implements SigningProvider {

    private final String projectId;
    private final String location;
    private final Map<String, String> keyRingMapping;

    /**
     * Create a GCP KMS signing provider stub.
     *
     * @param projectId    GCP project ID hosting the key rings
     * @param location     GCP location (e.g. "global", "us-east1")
     * @param keyRingMapping mapping from AdCP key identifiers to KMS key ring paths
     */
    public GcpKmsSigningProvider(String projectId, String location, Map<String, String> keyRingMapping) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.location = Objects.requireNonNull(location, "location");
        this.keyRingMapping = Map.copyOf(Objects.requireNonNull(keyRingMapping, "keyRingMapping"));
    }

    @Override
    public Signature sign(SigningContext context, SigningInput input) throws SigningException {
        throw new UnsupportedOperationException(
                "GCP KMS signing not yet implemented — track 4 v0.2");
    }
}