package org.adcontextprotocol.adcp.error;

/** An idempotency key has expired (TTL exceeded). */
public final class IdempotencyExpiredError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyExpiredError(String message) {
        super("IDEMPOTENCY_EXPIRED", message, null);
    }
}
