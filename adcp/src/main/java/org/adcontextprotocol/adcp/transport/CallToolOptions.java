package org.adcontextprotocol.adcp.transport;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Options for a single {@code callTool()} invocation.
 *
 * @param timeout     per-call timeout (overrides client default)
 * @param maxResponseBytes per-call body cap (overrides client default)
 * @param validateResponse whether to validate the response against schema
 */
public record CallToolOptions(
        @Nullable Duration timeout,
        @Nullable Long maxResponseBytes,
        boolean validateResponse
) {

    /** Default options: no timeout override, no body cap override, validation off. */
    public static final CallToolOptions DEFAULT = new CallToolOptions(null, null, false);

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private @Nullable Duration timeout;
        private @Nullable Long maxResponseBytes;
        private boolean validateResponse;

        private Builder() {}

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxResponseBytes(long maxResponseBytes) {
            if (maxResponseBytes <= 0) {
                throw new IllegalArgumentException(
                        "maxResponseBytes must be positive: " + maxResponseBytes);
            }
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        public Builder validateResponse(boolean validateResponse) {
            this.validateResponse = validateResponse;
            return this;
        }

        public CallToolOptions build() {
            return new CallToolOptions(timeout, maxResponseBytes, validateResponse);
        }
    }
}
