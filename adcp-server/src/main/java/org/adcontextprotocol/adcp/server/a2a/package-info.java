/**
 * A2A server-side transport support: request-handler wiring, JSON-RPC servlet
 * dispatch, agent-card discovery servlet, and
 * {@link org.a2aproject.sdk.server.agentexecution.AgentExecutor}
 * adaptation onto {@code AdcpPlatform}.
 *
 * <p>Deploy {@link org.adcontextprotocol.adcp.server.a2a.A2aServlet} for JSON-RPC
 * and {@link org.adcontextprotocol.adcp.server.a2a.A2aCardServlet} at
 * {@code /.well-known/agent-card.json} for agent discovery.
 */
@org.jspecify.annotations.NullMarked
package org.adcontextprotocol.adcp.server.a2a;
