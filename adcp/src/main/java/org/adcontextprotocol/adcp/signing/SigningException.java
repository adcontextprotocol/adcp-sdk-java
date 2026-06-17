package org.adcontextprotocol.adcp.signing;

/**
 * Exception thrown when outbound signing fails.
 */
public final class SigningException extends Exception {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SigningException(String message) {
        super(message);
    }

    public SigningException(String message, Throwable cause) {
        super(message, cause);
    }
}