package org.adcontextprotocol.adcp.error;

import org.jspecify.annotations.Nullable;

/** Client or agent configuration is invalid. */
public final class ConfigurationError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final @Nullable String configField;

    public ConfigurationError(String message, @Nullable String configField) {
        super("CONFIGURATION_ERROR", message, null);
        this.configField = configField;
    }

    public @Nullable String configField() {
        return configField;
    }
}
