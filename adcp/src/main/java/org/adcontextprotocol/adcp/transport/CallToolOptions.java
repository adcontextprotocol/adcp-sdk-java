package org.adcontextprotocol.adcp.transport;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Options for a single {@code callTool()} invocation.
 *
 * <p>{@code maxResponseBytes} overrides the response size cap for this call on
 * both the MCP and A2A transports. It is enforced while the body streams in;
 * an over-cap response throws
 * {@link org.adcontextprotocol.adcp.error.ResponseTooLargeError}. Resolution
 * order is call → agent ({@code AgentConfig.maxResponseBytes}) → client
 * ({@code AdcpClient.Builder.maxResponseBytes}, default 4 MiB).
 *
 * <p>Overriding the cap selects a transport connection built for that cap, so
 * varying it per call fragments the connection cache. Prefer setting it once on
 * the {@link org.adcontextprotocol.adcp.AgentConfig} for agents that routinely
 * answer with large catalogues.
 *
 * <p><b>Implementation status:</b> {@code timeout} is accepted for forward
 * compatibility but is <em>not yet wired</em> into the transport path; the
 * client-level {@code requestTimeout} applies instead.
 *
 * @param timeout     per-call timeout (overrides client default) — <em>reserved, not yet enforced</em>
 * @param maxResponseBytes per-call response cap in bytes (overrides agent and client defaults)
 * @param validateResponse whether to validate the response against schema
 */
public record CallToolOptions(
        @Nullable Duration timeout,
        @Nullable Long maxResponseBytes,
        boolean validateResponse
) {

    /**
     * Default options: no timeout override, no cap override (the agent or
     * client default applies), validation off.
     */
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

        /**
         * Caps this call's response at {@code maxResponseBytes}, overriding the
         * agent and client defaults. Must be positive — the cap cannot be
         * disabled.
         */
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
