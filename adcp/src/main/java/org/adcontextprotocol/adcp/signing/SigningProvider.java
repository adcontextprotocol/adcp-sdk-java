package org.adcontextprotocol.adcp.signing;

/**
 * Outbound signing SPI. Implementations select a key based on the
 * {@link SigningContext} and produce a {@link Signature} over the
 * {@link SigningInput}.
 */
@FunctionalInterface
public interface SigningProvider {
    Signature sign(SigningContext context, SigningInput input) throws SigningException;
}