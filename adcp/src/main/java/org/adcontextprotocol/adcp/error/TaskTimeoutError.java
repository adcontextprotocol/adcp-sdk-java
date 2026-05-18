package org.adcontextprotocol.adcp.error;

import org.jspecify.annotations.Nullable;

/** A task exceeded its working timeout. */
public final class TaskTimeoutError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final @Nullable String taskId;
    private final long timeoutMs;

    public TaskTimeoutError(@Nullable String taskId, long timeoutMs) {
        super("TASK_TIMEOUT",
                "Task timed out after " + timeoutMs + "ms"
                        + (taskId != null ? " (taskId=" + taskId + ")" : ""),
                null);
        this.taskId = taskId;
        this.timeoutMs = timeoutMs;
    }

    public @Nullable String taskId() {
        return taskId;
    }

    public long timeoutMs() {
        return timeoutMs;
    }
}
