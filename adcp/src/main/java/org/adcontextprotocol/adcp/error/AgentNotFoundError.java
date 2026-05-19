package org.adcontextprotocol.adcp.error;

import java.util.List;

/** The requested agent was not found in the client configuration. */
public final class AgentNotFoundError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String agentId;
    @SuppressWarnings("serial")
    private final List<String> availableAgents;

    public AgentNotFoundError(String agentId, List<String> availableAgents) {
        super("AGENT_NOT_FOUND",
                "Agent not found: " + agentId
                        + ". Available: " + availableAgents,
                null);
        this.agentId = agentId;
        this.availableAgents = List.copyOf(availableAgents);
    }

    public String agentId() {
        return agentId;
    }

    public List<String> availableAgents() {
        return availableAgents;
    }
}
