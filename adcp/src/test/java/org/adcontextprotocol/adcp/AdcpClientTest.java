package org.adcontextprotocol.adcp;

import org.adcontextprotocol.adcp.error.ConfigurationError;
import org.adcontextprotocol.adcp.http.SsrfPolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdcpClient} builder and lifecycle.
 */
class AdcpClientTest {

    private static final URI AGENT_URI = URI.create("https://agent.example.com");

    @Test
    void builder_creates_client() {
        try (AdcpClient client = AdcpClient.builder()
                .agent(AgentConfig.mcp("test", AGENT_URI))
                .build()) {
            assertNotNull(client);
            assertEquals("test", client.agent().id());
            assertEquals(AGENT_URI, client.agent().agentUri());
        }
    }

    @Test
    void builder_rejects_missing_agent() {
        assertThrows(ConfigurationError.class, () ->
                AdcpClient.builder().build());
    }

    @Test
    void builder_with_version() {
        try (AdcpClient client = AdcpClient.builder()
                .agent(AgentConfig.mcp("test", AGENT_URI))
                .adcpVersion(AdcpVersion.V3_1)
                .build()) {
            assertEquals(AdcpVersion.V3_1, client.adcpVersion());
        }
    }

    @Test
    void builder_with_permissive_ssrf() {
        // Should not throw — permissive policy allows localhost
        try (AdcpClient client = AdcpClient.builder()
                .agent(AgentConfig.mcp("test",
                        URI.create("http://localhost:8080")))
                .ssrfPolicy(SsrfPolicy.permissive())
                .build()) {
            assertNotNull(client);
        }
    }

    @Test
    void client_is_autocloseable() {
        AdcpClient client = AdcpClient.builder()
                .agent(AgentConfig.mcp("test", AGENT_URI))
                .build();
        assertDoesNotThrow(client::close);
    }

    @Test
    void close_is_idempotent() {
        AdcpClient client = AdcpClient.builder()
                .agent(AgentConfig.mcp("test", AGENT_URI))
                .build();
        client.close();
        assertDoesNotThrow(client::close);
    }

    @Test
    void builder_accepts_a2a_protocol() {
        AgentConfig a2aAgent = AgentConfig.builder()
                .id("a2a")
                .agentUri(AGENT_URI)
                .protocol(Protocol.A2A)
                .build();

        try (AdcpClient client = AdcpClient.builder()
                .agent(a2aAgent)
                .ssrfPolicy(SsrfPolicy.permissive())
                .build()) {
            assertEquals(Protocol.A2A, client.agent().protocol());
        }
    }

    @Test
    void a2a_callTool_accepts_null_args_without_npe() {
        AgentConfig a2aAgent = AgentConfig.builder()
                .id("a2a")
                .agentUri(URI.create("mailto:test@example.com"))
                .protocol(Protocol.A2A)
                .build();

        try (AdcpClient client = AdcpClient.builder()
                .agent(a2aAgent)
                .ssrfPolicy(SsrfPolicy.permissive())
                .build()) {
            assertThrows(org.adcontextprotocol.adcp.error.ProtocolError.class,
                    () -> client.callTool("get_products", null, java.util.Map.class));
        }
    }

    @Test
    void builder_accepts_string_version() {
        try (AdcpClient client = AdcpClient.builder()
                .agent(AgentConfig.mcp("test", AGENT_URI))
                .adcpVersion("3.0")
                .build()) {
            assertNotNull(client.adcpVersion());
            assertEquals(3, client.adcpVersion().majorVersion());
            assertEquals("3.0", client.adcpVersion().minorVersion());
        }
    }

    @Test
    void builder_rejects_cross_major_version() {
        assertThrows(org.adcontextprotocol.adcp.error.ConfigurationError.class,
                () -> AdcpClient.builder()
                        .agent(AgentConfig.mcp("test", AGENT_URI))
                        .adcpVersion(new AdcpVersion(2, null))
                        .build());
    }
}
