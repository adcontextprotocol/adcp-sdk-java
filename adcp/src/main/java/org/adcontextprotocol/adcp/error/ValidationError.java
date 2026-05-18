package org.adcontextprotocol.adcp.error;

import org.jspecify.annotations.Nullable;

/** Request or response validation failed. */
public final class ValidationError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final @Nullable String field;

    public ValidationError(String message, @Nullable String field) {
        super("VALIDATION_ERROR", message, null);
        this.field = field;
    }

    public @Nullable String field() {
        return field;
    }
}
