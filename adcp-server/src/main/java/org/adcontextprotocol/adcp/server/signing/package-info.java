/**
 * RFC 9421 canonicalizer, signer, verifier, and AdCP profile for the Java SDK.
 *
 * <p>This package implements the server-side RFC 9421 signature base construction
 * (canonicalizer), Signature-Input header generation (builder), Content-Digest
 * computation (RFC 9530), signing, verification, and the AdCP-specific profile
 * constraints for webhook and request signing.
 *
 * <p>Core SPI types live in {@code org.adcontextprotocol.adcp.signing} (the
 * {@code adcp} module). This package provides the concrete implementations.
 *
 * @see Rfc9421Canonicalizer
 * @see Rfc9421Signer
 * @see Rfc9421Verifier
 * @see AdcpSignatureProfile
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.server.signing;