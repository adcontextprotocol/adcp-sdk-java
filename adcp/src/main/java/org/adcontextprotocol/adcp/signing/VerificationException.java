package org.adcontextprotocol.adcp.signing;

/**
 * Exception thrown when inbound signature verification fails.
 *
 * <p>Carries an error code from the {@code webhook_signature_*} taxonomy:
 * <ul>
 *   <li>{@code webhook_signature_invalid} — signature does not verify</li>
 *   <li>{@code webhook_signature_key_unknown} — no key found for kid</li>
 *   <li>{@code webhook_signature_key_purpose_invalid} — key purpose mismatch</li>
 * </ul>
 */
public final class VerificationException extends Exception {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public VerificationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public VerificationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** Error code matching the webhook signature error taxonomy. */
    public String errorCode() {
        return errorCode;
    }
}