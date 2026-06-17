package org.adcontextprotocol.adcp.server.signing.jwks;

/**
 * Unchecked exception for JWKS resolution failures.
 *
 * <p>Wraps the root cause when JWKS fetching or parsing fails.
 * This allows the {@link VerificationKeyResolver} SPI to remain
 * unchecked-exception-clean while still surfacing errors to callers.
 */
public final class JwksResolutionException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public JwksResolutionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public JwksResolutionException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** Error code matching the webhook signature error taxonomy. */
    public String errorCode() {
        return errorCode;
    }
}