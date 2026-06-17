/**
 * Bouncy Castle FIPS signing provider for AdCP (optional).
 *
 * <p>This module provides a {@link org.adcontextprotocol.adcp.signing.SigningProvider}
 * backed by Bouncy Castle FIPS for deployments that require FIPS 140-validated
 * cryptography. The core signing path uses JDK 21's built-in Ed25519/ECDSA;
 * this module is only needed in FIPS-mandated environments.
 *
 * @see org.adcontextprotocol.adcp.signing.bc.BouncyCastleSigningProvider
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.signing.bc;