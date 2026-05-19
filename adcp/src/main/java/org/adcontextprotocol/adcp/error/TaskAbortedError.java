package org.adcontextprotocol.adcp.error;

import org.jspecify.annotations.Nullable;

/** A task was aborted by the caller or the agent. */
public final class TaskAbortedError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String taskId;

    public TaskAbortedError(String taskId, @Nullable String reason) {
        super("TASK_ABORTED",
                "Task aborted: " + taskId
                        + (reason != null ? " (" + reason + ")" : ""),
                null);
        this.taskId = taskId;
    }

    public String taskId() {
        return taskId;
    }
}
