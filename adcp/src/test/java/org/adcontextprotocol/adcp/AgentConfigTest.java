package org.adcontextprotocol.adcp;

import org.adcontextprotocol.adcp.auth.BasicCredentials;
import org.adcontextprotocol.adcp.auth.OAuthClientCredentials;
import org.adcontextprotocol.adcp.auth.OAuthTokens;
import org.adcontextprotocol.adcp.error.ConfigurationError;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentConfig}.
 */
class AgentConfigTest {

    private static final URI AGENT_URI = URI.create("https://agent.example.com");

    @Test
    void builder_creates_minimal_config() {
        AgentConfig config = AgentConfig.builder()
                .id("test-agent")
                .agentUri(AGENT_URI)
                .build();

        assertEquals("test-agent", config.id());
        assertEquals(AGENT_URI, config.agentUri());
        assertEquals(Protocol.MCP, config.protocol());
        assertNull(config.authToken());
        assertNull(config.basicAuth());
        assertTrue(config.extraHeaders().isEmpty());
    }

    @Test
    void static_factory_mcp_no_auth() {
        AgentConfig config = AgentConfig.mcp("a", AGENT_URI);

        assertEquals("a", config.id());
        assertEquals(Protocol.MCP, config.protocol());
        assertNull(config.authToken());
    }

    @Test
    void static_factory_mcp_with_token() {
        AgentConfig config = AgentConfig.mcp("a", AGENT_URI, "my-token");

        assertEquals("my-token", config.authToken());
    }

    @Test
    void builder_with_bearer_token() {
        AgentConfig config = AgentConfig.builder()
                .id("agent")
                .agentUri(AGENT_URI)
                .authToken("test-bearer-token")
                .build();

        assertEquals("test-bearer-token", config.authToken());
        assertNull(config.basicAuth());
    }

    @Test
    void builder_with_basic_auth() {
        AgentConfig config = AgentConfig.builder()
                .id("agent")
                .agentUri(AGENT_URI)
                .basicAuth(new BasicCredentials("user", "pass"))
                .build();

        assertNotNull(config.basicAuth());
        assertEquals("user", config.basicAuth().username());
    }

    @Test
    void builder_rejects_multiple_auth() {
        assertThrows(ConfigurationError.class, () ->
                AgentConfig.builder()
                        .id("agent")
                        .agentUri(AGENT_URI)
                        .authToken("tok")
                        .basicAuth(new BasicCredentials("u", "p"))
                        .build());
    }

    @Test
    void builder_rejects_missing_id() {
        assertThrows(ConfigurationError.class, () ->
                AgentConfig.builder()
                        .agentUri(AGENT_URI)
                        .build());
    }

    @Test
    void builder_rejects_missing_agent_uri() {
        assertThrows(ConfigurationError.class, () ->
                AgentConfig.builder()
                        .id("agent")
                        .build());
    }

    @Test
    void extra_headers_are_immutable() {
        var headers = new java.util.HashMap<String, String>();
        headers.put("X-Custom", "value");

        AgentConfig config = AgentConfig.builder()
                .id("agent")
                .agentUri(AGENT_URI)
                .extraHeaders(headers)
                .build();

        // Modifying the original map doesn't affect the config
        headers.put("X-New", "val");
        assertFalse(config.extraHeaders().containsKey("X-New"));

        // The returned map is also immutable
        assertThrows(UnsupportedOperationException.class,
                () -> config.extraHeaders().put("X-Fail", "val"));
    }

    @Test
    void builder_with_a2a_protocol() {
        AgentConfig config = AgentConfig.builder()
                .id("a2a-agent")
                .agentUri(AGENT_URI)
                .protocol(Protocol.A2A)
                .build();

        assertEquals(Protocol.A2A, config.protocol());
    }

    @Test
    void builder_with_oauth_client_credentials() {
        var oauthCC = new OAuthClientCredentials(
                "client-id", "client-secret",
                "https://auth.example.com/token", "read write");

        AgentConfig config = AgentConfig.builder()
                .id("agent")
                .agentUri(AGENT_URI)
                .oauthClientCredentials(oauthCC)
                .build();

        assertNotNull(config.oauthClientCredentials());
        assertEquals("client-id", config.oauthClientCredentials().clientId());
    }

    @Test
    void builder_with_adcp_version() {
        AgentConfig config = AgentConfig.builder()
                .id("agent")
                .agentUri(AGENT_URI)
                .adcpVersion(AdcpVersion.V3_1)
                .build();

        assertNotNull(config.adcpVersion());
        assertEquals(3, config.adcpVersion().majorVersion());
        assertEquals("3.1", config.adcpVersion().minorVersion());
    }
}
