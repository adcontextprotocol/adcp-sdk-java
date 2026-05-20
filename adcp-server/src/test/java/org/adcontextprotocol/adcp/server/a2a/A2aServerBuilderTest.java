package org.adcontextprotocol.adcp.server.a2a;

import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.spec.AgentCard;
import org.adcontextprotocol.adcp.error.ConfigurationError;
import org.adcontextprotocol.adcp.server.AdcpPlatform;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class A2aServerBuilderTest {

    @Test
    void build_requires_agent_name() {
        A2aServerBuilder builder = A2aServerBuilder.create(platform())
                .agentUrl("https://agent.example.com")
                .agentVersion("1.0.0");

        ConfigurationError error = assertThrows(ConfigurationError.class, builder::build);
        assertEquals("agentName", error.configField());
    }

    @Test
    void build_requires_agent_url() {
        A2aServerBuilder builder = A2aServerBuilder.create(platform())
                .agentName("test-agent")
                .agentVersion("1.0.0");

        ConfigurationError error = assertThrows(ConfigurationError.class, builder::build);
        assertEquals("agentUrl", error.configField());
    }

    @Test
    void build_requires_agent_version() {
        A2aServerBuilder builder = A2aServerBuilder.create(platform())
                .agentName("test-agent")
                .agentUrl("https://agent.example.com");

        ConfigurationError error = assertThrows(ConfigurationError.class, builder::build);
        assertEquals("agentVersion", error.configField());
    }

    @Test
    void build_returns_default_request_handler() {
        DefaultRequestHandler handler = A2aServerBuilder.create(platform())
                .agentName("test-agent")
                .agentUrl("https://agent.example.com")
                .agentVersion("1.0.0")
                .build();

        assertNotNull(handler);
    }

    @Test
    void build_exposes_agent_card() {
        A2aServerBuilder builder = A2aServerBuilder.create(platform())
                .agentName("test-agent")
                .agentUrl("https://agent.example.com")
                .agentVersion("1.0.0");

        builder.build();
        AgentCard card = builder.getAgentCard();

        assertEquals("test-agent", card.name());
        assertEquals("https://agent.example.com", card.url());
        assertEquals("1.0.0", card.version());
    }

    private static AdcpPlatform platform() {
        return new AdcpPlatform() {
            @Override
            public Set<String> supportedTools() {
                return Set.of("echo");
            }
        };
    }
}
