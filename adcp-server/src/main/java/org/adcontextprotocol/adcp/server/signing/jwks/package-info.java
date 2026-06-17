/**
 * JWKS-based verification key resolvers for inbound AdCP signature verification.
 *
 * <p>Provides HTTP-fetched, cached, and static JWKS resolution with SSRF protection,
 * single-flight deduplication, cooldown-gated refresh, and AdCP-specific key
 * validation (adcp_use, key_ops).
 *
 * @see org.adcontextprotocol.adcp.signing.VerificationKeyResolver
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.server.signing.jwks;