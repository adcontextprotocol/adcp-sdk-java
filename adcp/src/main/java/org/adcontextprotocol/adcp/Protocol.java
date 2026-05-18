package org.adcontextprotocol.adcp;

/**
 * Transport protocol used to communicate with an agent.
 *
 * <p>Determines which dispatch path {@code ProtocolClient} uses:
 * <ul>
 *   <li>{@link #MCP} — Model Context Protocol (StreamableHTTP + SSE fallback)</li>
 *   <li>{@link #A2A} — Agent-to-Agent protocol (JSON-RPC 2.0 + SSE streaming)</li>
 * </ul>
 */
public enum Protocol {

    /** Model Context Protocol — the primary transport for AdCP. */
    MCP,

    /** Agent-to-Agent protocol (v0.4). */
    A2A
}
