package org.adcontextprotocol.adcp.error;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/** The agent does not support the requested protocol version. */
public final class VersionUnsupportedError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final @Nullable String taskType;
    private final String reason;
    private final @Nullable String actualVersion;
    private final @Nullable URI agentUri;

    public VersionUnsupportedError(
            @Nullable String taskType,
            String reason,
            @Nullable String actualVersion,
            @Nullable URI agentUri) {
        super("VERSION_UNSUPPORTED",
                "Version unsupported: " + reason
                        + (taskType != null ? " (task=" + taskType + ")" : ""),
                null);
        this.taskType = taskType;
        this.reason = reason;
        this.actualVersion = actualVersion;
        this.agentUri = agentUri;
    }

    public @Nullable String taskType() {
        return taskType;
    }

    /** {@code "version"}, {@code "idempotency"}, or a domain-specific reason. */
    public String reason() {
        return reason;
    }

    public @Nullable String actualVersion() {
        return actualVersion;
    }

    public @Nullable URI agentUri() {
        return agentUri;
    }
}
