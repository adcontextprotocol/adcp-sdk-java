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

    @Test
    @SuppressWarnings("unchecked")
    void callTool_get_adcp_capabilities_returns_response() {
        AgentConfig agent = AgentConfig.mcp("mock", mockServerUri());

        try (AdcpClient client = AdcpClient.builder()
                .agent(agent)
                .adcpVersion(AdcpVersion.V3)
                .ssrfPolicy(SsrfPolicy.permissive())
                .build()) {
            // get_adcp_capabilities requires no arguments and every
            // mock-server specialism should support it
            Map<String, Object> result = client.callTool(
                    "get_adcp_capabilities", Map.of(), Map.class);
            assertNotNull(result);
        }
    }
}
