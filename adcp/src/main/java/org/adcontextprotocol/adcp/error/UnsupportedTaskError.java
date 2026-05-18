package org.adcontextprotocol.adcp.error;

/** The agent does not support the requested tool/task. */
public final class UnsupportedTaskError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String taskName;

    public UnsupportedTaskError(String taskName) {
        super("UNSUPPORTED_TASK",
                "Unsupported task: " + taskName,
                null);
        this.taskName = taskName;
    }

    public String taskName() {
        return taskName;
    }
}
