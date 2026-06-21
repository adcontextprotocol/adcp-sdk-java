/**
 * Transport dispatch for AdCP caller operations.
 *
 * <p>Routes tool calls over MCP or A2A, applies auth/header merging,
 * version envelopes, SSRF validation, and transport-specific retry logic.
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.transport;
