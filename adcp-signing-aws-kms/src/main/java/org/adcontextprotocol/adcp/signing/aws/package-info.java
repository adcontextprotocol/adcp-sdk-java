/**
 * AWS KMS signing provider for AdCP.
 *
 * <p>This module provides a {@link org.adcontextprotocol.adcp.signing.SigningProvider}
 * backed by AWS Key Management Service. The KMS client is lazy-initialised and
 * only touches the AWS API on the first sign/verify call.
 *
 * @see org.adcontextprotocol.adcp.signing.aws.AwsKmsSigningProvider
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.signing.aws;