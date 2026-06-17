/**
 * Google Cloud KMS signing provider for AdCP.
 *
 * <p>This module provides a {@link org.adcontextprotocol.adcp.signing.SigningProvider}
 * backed by Google Cloud Key Management Service. The KMS client is lazy-initialised
 * and only touches the GCP API on the first sign/verify call.
 *
 * @see org.adcontextprotocol.adcp.signing.gcp.GcpKmsSigningProvider
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.signing.gcp;