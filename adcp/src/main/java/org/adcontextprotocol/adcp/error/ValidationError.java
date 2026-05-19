package org.adcontextprotocol.adcp.error;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Request or response validation failed.
 *
 * <p>The {@link #path()} carries a JSON-pointer path as a list of segments
 * (e.g. {@code ["products", "3", "formats", "0", "duration"]}), matching
 * the TS SDK's wire format. The {@link #schemaUri()} is the failing
 * schema's {@code $id} when available.
 */
public final class ValidationError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // List.copyOf() returns a Serializable impl
    private final List<String> path;
    private final @Nullable String schemaUri;

    public ValidationError(String message, @Nullable List<String> path,
                           @Nullable String schemaUri) {
        super("VALIDATION_ERROR", message, null);
        this.path = path != null ? List.copyOf(path) : List.of();
        this.schemaUri = schemaUri;
    }

    public ValidationError(String message, @Nullable String field) {
        this(message, field != null ? List.of(field) : null, null);
    }

    /** JSON-pointer path to the failing field (may be empty). */
    public List<String> path() {
        return path;
    }

    /** The {@code $id} of the schema that failed validation, if available. */
    public @Nullable String schemaUri() {
        return schemaUri;
    }
}
