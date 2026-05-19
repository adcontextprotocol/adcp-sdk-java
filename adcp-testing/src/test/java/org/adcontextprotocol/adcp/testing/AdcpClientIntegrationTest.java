package org.adcontextprotocol.adcp.testing;

import org.adcontextprotocol.adcp.AdcpClient;
import org.adcontextprotocol.adcp.AdcpVersion;
import org.adcontextprotocol.adcp.AgentConfig;
import org.adcontextprotocol.adcp.Protocol;
import org.adcontextprotocol.adcp.http.SsrfPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that uses {@link AdcpClient} to call the
 * {@code @adcp/sdk/mock-server} sidecar when available.
 *
 * <p>Skipped unless {@code ADCP_MOCK_SERVER_URL} is set. CI provides it
 * via the storyboard workflow.
 *
 * <p>Validates the full caller-side stack:
 * {@code AdcpClient} → {@code ProtocolClient} → {@code McpCaller} →
 * MCP transport → mock-server.
 */
@EnabledIfEnvironmentVariable(
        named = "ADCP_MOCK_SERVER_URL",
        matches = ".+",
        disabledReason = "Set ADCP_MOCK_SERVER_URL to run; CI sets it automatically"
)
class AdcpClientIntegrationTest {

    private static URI mockServerUri() {
        return URI.create(System.getenv("ADCP_MOCK_SERVER_URL"));
    }

    @Test
    void client_builder_configures_against_mock_server() {
        AgentConfig agent = AgentConfig.mcp("mock", mockServerUri());

        try (AdcpClient client = AdcpClient.builder()
                .agent(agent)
                .adcpVersion(AdcpVersion.V3)
                .ssrfPolicy(SsrfPolicy.permissive())
                .build()) {
            assertNotNull(client);
            assertEquals(Protocol.MCP, client.agent().protocol());
            assertEquals(mockServerUri(), client.agent().agentUri());
        }
    }

    /**
     * Exercises the full caller stack against a live MCP-speaking server.
     *
     * <p>The current {@code @adcp/sdk} mock-server is a REST stub, not an
     * MCP server, so this test is guarded behind a separate env var
     * ({@code ADCP_MCP_SERVER_URL}) until the mock-server gains MCP
     * support.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "ADCP_MCP_SERVER_URL",
            matches = ".+",
            disabledReason = "Set ADCP_MCP_SERVER_URL to run against an MCP-speaking server"
    )
    @SuppressWarnings("unchecked")
    void callTool_get_adcp_capabilities_returns_response() {
        URI mcpUri = URI.create(System.getenv("ADCP_MCP_SERVER_URL"));
        AgentConfig agent = AgentConfig.mcp("mock", mcpUri);

        try (AdcpClient client = AdcpClient.builder()
                .agent(agent)
                .adcpVersion(AdcpVersion.V3)
                .ssrfPolicy(SsrfPolicy.permissive())
                .build()) {
            Map<String, Object> result = client.callTool(
                    "get_adcp_capabilities", Map.of(), Map.class);

            // Validate spec shape — not just non-null
            assertNotNull(result, "get_adcp_capabilities should return a response");
            assertFalse(result.isEmpty(),
                    "Response should contain at least one field");

            // The response should carry either 'capabilities' or version fields
            // depending on the mock-server implementation
            boolean hasCapabilities = result.containsKey("capabilities");
            boolean hasVersion = result.containsKey("adcp_version")
                    || result.containsKey("adcp_major_version");
            assertTrue(hasCapabilities || hasVersion,
                    "Response should contain 'capabilities' or version fields, got: "
                            + result.keySet());
        }
    }
}
