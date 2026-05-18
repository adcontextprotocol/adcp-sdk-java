package org.adcontextprotocol.adcp.error;

/** An idempotency key collided with an in-flight or completed request. */
public final class IdempotencyConflictError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictError(String message) {
        super("IDEMPOTENCY_CONFLICT", message, null);
    }
}
