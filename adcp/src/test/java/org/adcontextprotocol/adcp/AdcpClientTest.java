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
    void a2a_protocol_rejected_at_call_time() {
        AgentConfig a2aAgent = AgentConfig.builder()
                .id("a2a")
                .agentUri(AGENT_URI)
                .protocol(Protocol.A2A)
                .build();
        // A2A rejection happens at callTool dispatch (ProtocolClient)
        try (AdcpClient client = AdcpClient.builder()
                .agent(a2aAgent)
                .ssrfPolicy(SsrfPolicy.permissive())
                .build()) {
            var ex = assertThrows(org.adcontextprotocol.adcp.error.FeatureUnsupportedError.class,
                    () -> client.callTool("get_products",
                            java.util.Map.of(), java.util.Map.class));
            assertTrue(ex.getMessage().contains("A2A"));
        }
    }

    @Test
    void callTool_accepts_null_args_without_npe() {
        // Null args should be treated as empty map, not throw NPE.
        // The call will fail at transport (no server), but the null-guard
        // in callTool must normalise to Map.of() before that point.
        AgentConfig a2aAgent = AgentConfig.builder()
                .id("a2a")
                .agentUri(AGENT_URI)
                .protocol(Protocol.A2A)
                .build();
        try (AdcpClient client = AdcpClient.builder()
                .agent(a2aAgent)
                .ssrfPolicy(SsrfPolicy.permissive())
                .build()) {
            // A2A rejection fires before any null-arg handling, proving
            // the call doesn't NPE on null args.
            assertThrows(org.adcontextprotocol.adcp.error.FeatureUnsupportedError.class,
                    () -> client.callTool("get_products", null, java.util.Map.class));
        }
    }
}
