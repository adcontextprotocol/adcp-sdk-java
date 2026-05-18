package org.adcontextprotocol.adcp.auth;

import org.adcontextprotocol.adcp.AgentConfig;
import org.adcontextprotocol.adcp.Protocol;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AuthTokenResolver}.
 */
class AuthTokenResolverTest {

    private static final URI AGENT_URI = URI.create("https://agent.example.com");

    @Test
    void resolve_bearer_token() {
        AgentConfig config = AgentConfig.builder()
                .id("a")
                .agentUri(AGENT_URI)
                .authToken("my-token")
                .build();

        Map<String, String> headers = AuthTokenResolver.resolve(config);

        assertEquals("Bearer my-token", headers.get("Authorization"));
        assertEquals("my-token", headers.get("x-adcp-auth"));
    }

    @Test
    void resolve_basic_auth() {
        AgentConfig config = AgentConfig.builder()
                .id("a")
                .agentUri(AGENT_URI)
                .basicAuth(new BasicCredentials("user", "pass"))
                .build();

        Map<String, String> headers = AuthTokenResolver.resolve(config);

        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "user:pass".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, headers.get("Authorization"));
        assertFalse(headers.containsKey("x-adcp-auth"));
    }

    @Test
    void resolve_oauth_tokens() {
        AgentConfig config = AgentConfig.builder()
                .id("a")
                .agentUri(AGENT_URI)
                .oauthTokens(OAuthTokens.bearer("access-tok-123"))
                .build();

        Map<String, String> headers = AuthTokenResolver.resolve(config);

        assertEquals("Bearer access-tok-123", headers.get("Authorization"));
        assertFalse(headers.containsKey("x-adcp-auth"));
    }

    @Test
    void resolve_no_auth_returns_empty() {
        AgentConfig config = AgentConfig.mcp("a", AGENT_URI);

        Map<String, String> headers = AuthTokenResolver.resolve(config);

        assertTrue(headers.isEmpty());
    }

    @Test
    void resolved_headers_are_immutable() {
        AgentConfig config = AgentConfig.mcp("a", AGENT_URI, "tok");

        Map<String, String> headers = AuthTokenResolver.resolve(config);

        assertThrows(UnsupportedOperationException.class,
                () -> headers.put("X-Evil", "val"));
    }
}
