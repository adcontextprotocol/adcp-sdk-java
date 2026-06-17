/**
 * JWS verification for AdCP revocation lists.
 *
 * <p>Supports both compact serialization ({@code header.payload.signature})
 * and JSON general serialization, with EdDSA and ES256 algorithms.
 *
 * @see JwsVerifier
 * @see JwsDocument
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.server.signing.jws;