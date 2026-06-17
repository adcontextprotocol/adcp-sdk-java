/**
 * AdCP L1 signing SPI — interfaces for request and webhook signing/verification.
 *
 * <p>This package defines the core signing surface shared by both the caller
 * (verification) and server (signing) sides. Provider implementations live in
 * separate modules ({@code adcp-signing-aws-kms}, {@code adcp-signing-gcp-kms},
 * {@code adcp-signing-bouncycastle}).
 *
 * @see SigningContext
 * @see SigningProvider
 * @see VerificationKeyResolver
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.signing;