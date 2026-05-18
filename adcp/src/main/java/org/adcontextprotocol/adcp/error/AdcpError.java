package org.adcontextprotocol.adcp.error;

import org.jspecify.annotations.Nullable;

/**
 * Base class for all AdCP SDK errors. Each subclass carries a unique
 * {@link #code()} string that callers can switch on.
 *
 * <p>Mirrors the TS SDK's {@code ADCPError} hierarchy. All AdCP errors
 * are unchecked ({@link RuntimeException}) — callers who want to handle
 * them catch specific subclasses.
 */
public abstract sealed class AdcpError extends RuntimeException
        permits ProtocolError,
                AuthenticationRequiredError,
                TaskTimeoutError,
                TaskAbortedError,
                DeferredTaskError,
                ValidationError,
                ConfigurationError,
                VersionUnsupportedError,
                AgentNotFoundError,
                UnsupportedTaskError,
                FeatureUnsupportedError,
                ResponseTooLargeError,
                IdempotencyConflictError,
                IdempotencyExpiredError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String code;
    @SuppressWarnings("serial")
    private final @Nullable Object details;

    protected AdcpError(String code, String message, @Nullable Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    protected AdcpError(String code, String message, @Nullable Object details,
                        @Nullable Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    /** Stable error code for programmatic matching (e.g. "PROTOCOL_ERROR"). */
    public String code() {
        return code;
    }

    /** Optional structured details about the error. */
    public @Nullable Object details() {
        return details;
    }
}
