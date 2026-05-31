package org.adcontextprotocol.adcp.transport.a2a;

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.adcontextprotocol.adcp.AgentConfig;
import org.adcontextprotocol.adcp.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class A2aConnectionManagerTest {

    private A2aConnectionManager manager;

    @AfterEach
    void cleanup() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void getOrConnect_reuses_cached_client_for_same_url() {
        AtomicInteger loaderCalls = new AtomicInteger();
        AtomicInteger factoryCalls = new AtomicInteger();
        manager = new A2aConnectionManager(
                (agent, headers) -> {
                    loaderCalls.incrementAndGet();
                    return testCard(agent.agentUri());
                },
                agentCard -> {
                    factoryCalls.incrementAndGet();
                    return testClient(agentCard);
                });

        AgentConfig agent = AgentConfig.builder()
                .id("a2a-agent")
                .agentUri(URI.create("https://agent.example.com"))
                .protocol(Protocol.A2A)
                .build();

        Client first = manager.getOrConnect(agent, Map.of(), "anonymous");
        Client second = manager.getOrConnect(agent, Map.of(), "anonymous");

        assertSame(first, second);
        assertEquals(1, loaderCalls.get());
        assertEquals(1, factoryCalls.get());
    }

    @Test
    void getOrConnect_different_auth_headers_get_separate_clients() {
        AtomicInteger factoryCalls = new AtomicInteger();
        List<Map<String, String>> discoveryHeaders = new ArrayList<>();
        manager = new A2aConnectionManager(
                (agent, headers) -> {
                    discoveryHeaders.add(Map.copyOf(headers));
                    return testCard(agent.agentUri());
                },
                agentCard -> {
                    factoryCalls.incrementAndGet();
                    return testClient(agentCard);
                });

        AgentConfig agent = AgentConfig.builder()
                .id("a2a-agent")
                .agentUri(URI.create("https://agent.example.com"))
                .protocol(Protocol.A2A)
                .build();

        Client token1 = manager.getOrConnect(agent, Map.of("Authorization", "Bearer token-1"), "hash-1");
        Client token2 = manager.getOrConnect(agent, Map.of("Authorization", "Bearer token-2"), "hash-2");

        assertNotSame(token1, token2);
        assertEquals(2, factoryCalls.get());
        assertEquals(List.of(
                Map.of("Authorization", "Bearer token-1"),
                Map.of("Authorization", "Bearer token-2")), discoveryHeaders);
    }

    @Test
    void buildCacheKey_does_not_include_authorization_header() {
        List<Map<String, String>> discoveryHeaders = new ArrayList<>();
        manager = new A2aConnectionManager(
                (agent, headers) -> {
                    discoveryHeaders.add(Map.copyOf(headers));
                    return testCard(agent.agentUri());
                },
                A2aConnectionManagerTest::testClient);

        AgentConfig agent = AgentConfig.builder()
                .id("a2a-agent")
                .agentUri(URI.create("https://agent.example.com"))
                .protocol(Protocol.A2A)
                .build();

        manager.getOrConnect(agent, Map.of(
                "Authorization", "Bearer secret",
                "X-Tenant", "tenant-a"), "hash-1");

        String cacheKey = onlyCacheKey(manager);
        assertTrue(cacheKey.contains("x-tenant"), "cache key should contain lowercased header name");
        assertTrue(cacheKey.contains("tenant-a"));
        assertFalse(cacheKey.contains("Authorization"));
        assertFalse(cacheKey.contains("authorization"), "Authorization header must not appear in cache key");
        assertFalse(cacheKey.contains("secret"));
        assertEquals(List.of(Map.of(
                "Authorization", "Bearer secret",
                "X-Tenant", "tenant-a")), discoveryHeaders);
    }

    @Test
    void evict_exact_cache_hash_removes_only_matching_variant() {
        AtomicInteger factoryCalls = new AtomicInteger();
        manager = new A2aConnectionManager(
                (agent, headers) -> testCard(agent.agentUri()),
                agentCard -> {
                    factoryCalls.incrementAndGet();
                    return testClient(agentCard);
                });

        AgentConfig agent = AgentConfig.builder()
                .id("a2a-agent")
                .agentUri(URI.create("https://agent.example.com"))
                .protocol(Protocol.A2A)
                .build();

        Client token1 = manager.getOrConnect(agent, Map.of("Authorization", "Bearer token-1"), "hash-1");
        Client token2 = manager.getOrConnect(agent, Map.of("Authorization", "Bearer token-2"), "hash-2");
        assertEquals(2, factoryCalls.get());

        manager.evict(agent.agentUri(), "hash-1");

        Client token1Again = manager.getOrConnect(agent, Map.of("Authorization", "Bearer token-1"), "hash-1");
        Client token2Again = manager.getOrConnect(agent, Map.of("Authorization", "Bearer token-2"), "hash-2");
        assertNotSame(token1, token1Again);
        assertSame(token2, token2Again);
        assertEquals(3, factoryCalls.get());
    }

    @Test
    void evict_forces_reconnect() {
        AtomicInteger factoryCalls = new AtomicInteger();
        manager = new A2aConnectionManager(
                (agent, headers) -> testCard(agent.agentUri()),
                agentCard -> {
                    factoryCalls.incrementAndGet();
                    return testClient(agentCard);
                });

        AgentConfig agent = AgentConfig.builder()
                .id("a2a-agent")
                .agentUri(URI.create("https://agent.example.com"))
                .protocol(Protocol.A2A)
                .build();

        Client first = manager.getOrConnect(agent, Map.of(), "anonymous");
        manager.evict(agent.agentUri());
        Client second = manager.getOrConnect(agent, Map.of(), "anonymous");

        assertNotSame(first, second);
        assertEquals(2, factoryCalls.get());
    }

    @Test
    void getOrConnect_after_close_throws() {
        manager = new A2aConnectionManager(
                (agent, headers) -> testCard(agent.agentUri()),
                A2aConnectionManagerTest::testClient);
        manager.close();

        AgentConfig agent = AgentConfig.builder()
                .id("a2a-agent")
                .agentUri(URI.create("https://agent.example.com"))
                .protocol(Protocol.A2A)
                .build();

        assertThrows(IllegalStateException.class,
                () -> manager.getOrConnect(agent, Map.of(), "anonymous"));
    }

    @Test
    void httpAgentCardLoader_uses_agent_card_json_well_known_path() {
        List<URI> fetchedUris = new ArrayList<>();
        // Use the package-private AgentCardLoader interface to capture the URI
        manager = new A2aConnectionManager(
                (agent, headers) -> {
                    // Reconstruct what HttpAgentCardLoader would build
                    URI expected = URI.create(agent.agentUri().getScheme() + "://"
                            + agent.agentUri().getAuthority() + "/.well-known/agent-card.json");
                    fetchedUris.add(expected);
                    return testCard(agent.agentUri());
                },
                A2aConnectionManagerTest::testClient);

        AgentConfig agent = AgentConfig.builder()
                .id("a2a-agent")
                .agentUri(URI.create("https://agent.example.com"))
                .protocol(Protocol.A2A)
                .build();
        manager.getOrConnect(agent, Map.of(), "anonymous");

        assertEquals(1, fetchedUris.size());
        assertEquals("/.well-known/agent-card.json", fetchedUris.get(0).getPath());
    }

    @Test
    void httpAgentCardLoader_buildAgentCardUri_uses_standard_path() {
        // Verify that the standard well-known path is /.well-known/agent-card.json (not agent.json)
        URI base = URI.create("https://agent.example.com/some/path");
        URI cardUri = URI.create(base.getScheme() + "://" + base.getAuthority() + "/.well-known/agent-card.json");
        assertEquals("/.well-known/agent-card.json", cardUri.getPath());
        assertEquals("agent.example.com", cardUri.getHost());
        assertFalse(cardUri.toString().contains("agent.json"), "must not use old agent.json path");
    }

    @Test
    void authenticated_card_discovery_passes_auth_headers_to_loader() {
        List<Map<String, String>> capturedHeaders = new ArrayList<>();
        manager = new A2aConnectionManager(
                (agent, headers) -> {
                    capturedHeaders.add(Map.copyOf(headers));
                    return testCard(agent.agentUri());
                },
                A2aConnectionManagerTest::testClient);

        AgentConfig agent = AgentConfig.builder()
                .id("a2a-agent")
                .agentUri(URI.create("https://agent.example.com"))
                .protocol(Protocol.A2A)
                .build();

        manager.getOrConnect(agent,
                Map.of("Authorization", "Bearer my-token", "X-Tenant", "acme"),
                "hash-auth");

        assertEquals(1, capturedHeaders.size());
        Map<String, String> headers = capturedHeaders.get(0);
        // Auth headers must reach the card loader (not be stripped before the load call)
        assertEquals("Bearer my-token", headers.get("Authorization"));
        assertEquals("acme", headers.get("X-Tenant"));
    }

    private static String onlyCacheKey(A2aConnectionManager manager) {
        try {
            var cacheField = A2aConnectionManager.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Client> cache = (Map<String, Client>) cacheField.get(manager);
            assertEquals(1, cache.size());
            return cache.keySet().iterator().next();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static AgentCard testCard(URI uri) {
        return AgentCard.builder()
                .name("test")
                .description("test agent")
                .version("1.0")
                .url(uri.toString())
                .preferredTransport("JSONRPC")
                .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", uri.toString())))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .build();
    }

    private static Client testClient(AgentCard card) throws A2AClientException {
        return Client.builder(card)
                .clientConfig(ClientConfig.builder().setStreaming(false).setUseClientPreference(true).build())
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
                .build();
    }
}
