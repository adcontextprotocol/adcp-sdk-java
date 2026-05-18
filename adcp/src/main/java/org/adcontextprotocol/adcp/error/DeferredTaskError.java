package org.adcontextprotocol.adcp.error;

/** A task was deferred for async processing. Carries the deferral token. */
public final class DeferredTaskError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String token;

    public DeferredTaskError(String token) {
        super("TASK_DEFERRED", "Task deferred with token: " + token, null);
        this.token = token;
    }

    public String token() {
        return token;
    }
}
