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
import org.adcontextprotocol.adcp.http.AdcpHttpClient;
import org.adcontextprotocol.adcp.http.SsrfPolicy;
import org.adcontextprotocol.adcp.schema.AdcpObjectMapperFactory;
import org.adcontextprotocol.adcp.transport.CallToolOptions;
import org.adcontextprotocol.adcp.transport.ProtocolClient;
import org.adcontextprotocol.adcp.transport.mcp.McpConnectionManager;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the response size cap reaches the transport that enforces it,
 * and that the per-client → per-agent → per-call override order is honoured.
 *
 * <p>The cap is baked into the transport's {@code HttpClient} at connect time,
 * so "is the override wired" is answerable by recording the cap the client
 * factory is handed. A regression here is silent — the call still works, it is
 * just capped at the wrong number — which is exactly why it is asserted.
 */
class ResponseSizeCapWiringTest {

    /** An unroutable-but-fast-failing local port: the call fails, the cap is still recorded. */
    private static final URI AGENT_URI = URI.create("http://127.0.0.1:1/");

    @Test
    void client_default_cap_is_four_mebibytes() {
        assertEquals(4L * 1024 * 1024, AdcpHttpClient.DEFAULT_MAX_CALL_RESPONSE_BYTES);
        try (AdcpHttpClient http = AdcpHttpClient.builder().build()) {
            assertEquals(4L * 1024 * 1024, http.maxCallResponseBytes());
        }
    }

    @Test
    void default_cap_is_used_when_nothing_overrides_it() {
        List<Long> caps = capsSeenBy(agent(null), CallToolOptions.DEFAULT);

        assertTrue(caps.size() >= 1, "the factory should have been asked for a client");
        assertEquals(AdcpHttpClient.DEFAULT_MAX_CALL_RESPONSE_BYTES, caps.get(0));
    }

    @Test
    void agent_config_overrides_the_client_default() {
        List<Long> caps = capsSeenBy(agent(777_000L), CallToolOptions.DEFAULT);

        assertEquals(777_000L, caps.get(0));
        assertNotEquals(AdcpHttpClient.DEFAULT_MAX_CALL_RESPONSE_BYTES, caps.get(0));
    }

    @Test
    void call_options_override_the_agent_setting() {
        List<Long> caps = capsSeenBy(agent(777_000L),
                CallToolOptions.builder().maxResponseBytes(31_337).build());

        assertEquals(31_337L, caps.get(0));
    }

    @Test
    void connections_are_cached_per_cap() {
        RecordingFactory factory = new RecordingFactory();
        try (A2aConnectionManager manager = new A2aConnectionManager(
                (cfg, headers, cap) -> card(cfg.agentUri()), factory)) {

            AgentConfig agent = agent(null);
            Client first = manager.getOrConnect(agent, Map.of(), "hash", 1_000L);
            Client second = manager.getOrConnect(agent, Map.of(), "hash", 1_000L);
            Client third = manager.getOrConnect(agent, Map.of(), "hash", 2_000L);

            assertEquals(first, second, "same cap should reuse the cached client");
            assertNotEquals(first, third,
                    "a different cap must not reuse a client built for the old cap");
            assertEquals(List.of(1_000L, 2_000L), factory.caps);
        }
    }

    @Test
    void evict_removes_every_cap_variant() {
        RecordingFactory factory = new RecordingFactory();
        try (A2aConnectionManager manager = new A2aConnectionManager(
                (cfg, headers, cap) -> card(cfg.agentUri()), factory)) {

            AgentConfig agent = agent(null);
            manager.getOrConnect(agent, Map.of(), "hash", 1_000L);
            manager.getOrConnect(agent, Map.of(), "hash", 2_000L);
            manager.evict(agent.agentUri(), "hash");

            manager.getOrConnect(agent, Map.of(), "hash", 1_000L);
            assertEquals(List.of(1_000L, 2_000L, 1_000L), factory.caps,
                    "both cap variants should have been evicted and rebuilt on demand");
        }
    }

    @Test
    void transport_http_clients_are_released_when_their_connection_is_evicted() {
        // Neither JdkA2AHttpClient nor Client.close() releases the JDK HTTP
        // client underneath, and each one owns a selector thread. A caller that
        // varies maxResponseBytes per call would otherwise accumulate them
        // without bound.
        AdcpHttpClient http = AdcpHttpClient.builder().build();
        A2aConnectionManager.DefaultClientFactory factory =
                new A2aConnectionManager.DefaultClientFactory(http);
        try (http; A2aConnectionManager manager = new A2aConnectionManager(
                (cfg, headers, cap) -> card(cfg.agentUri()), factory)) {

            AgentConfig agent = agent(null);
            for (long cap = 1_000; cap <= 20_000; cap += 1_000) {
                manager.getOrConnect(agent, Map.of(), "hash", cap);
            }
            assertTrue(factory.pooledClientCount() > 0);

            manager.evict(agent.agentUri(), "hash");
            assertEquals(0, factory.pooledClientCount(),
                    "every cap variant's HTTP client should be released on eviction");
        }
    }

    @Test
    void transport_http_clients_are_shared_while_the_cap_is_shared() {
        AdcpHttpClient http = AdcpHttpClient.builder().build();
        A2aConnectionManager.DefaultClientFactory factory =
                new A2aConnectionManager.DefaultClientFactory(http);
        try (http; A2aConnectionManager manager = new A2aConnectionManager(
                (cfg, headers, cap) -> card(cfg.agentUri()), factory)) {

            // Five agents, all on the default cap: one HTTP client between them.
            for (int i = 0; i < 5; i++) {
                manager.getOrConnect(agent(null), Map.of(), "hash-" + i, null);
            }
            assertEquals(1, factory.pooledClientCount());
        }
    }

    @Test
    void a_non_positive_override_is_rejected() {
        RecordingFactory factory = new RecordingFactory();
        try (A2aConnectionManager manager = new A2aConnectionManager(
                (cfg, headers, cap) -> card(cfg.agentUri()), factory)) {
            AgentConfig agent = agent(null);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> manager.getOrConnect(agent, Map.of(), "hash", 0L));
        }
    }

    // -- helpers --

    /**
     * Runs one {@code callTool} through a real {@link ProtocolClient} and
     * returns the caps the client factory was handed. The call itself fails
     * (nothing is listening) — the point is the cap that was resolved first.
     */
    private static List<Long> capsSeenBy(AgentConfig agent, CallToolOptions options) {
        RecordingFactory factory = new RecordingFactory();
        A2aConnectionManager a2a = new A2aConnectionManager(
                (cfg, headers, cap) -> card(cfg.agentUri()), factory);
        try (ProtocolClient client = new ProtocolClient(
                AdcpObjectMapperFactory.create(), SsrfPolicy.permissive(), null,
                new McpConnectionManager(Duration.ofSeconds(1)), a2a)) {
            client.callTool(agent, "get_products", Map.of(), Object.class, options);
        } catch (RuntimeException expected) {
            // connection refused — we only care about the resolved cap
        }
        return factory.caps;
    }

    private static AgentConfig agent(Long maxResponseBytes) {
        AgentConfig.Builder builder = AgentConfig.builder()
                .id("a2a-agent")
                .agentUri(AGENT_URI)
                .protocol(Protocol.A2A);
        if (maxResponseBytes != null) {
            builder.maxResponseBytes(maxResponseBytes);
        }
        return builder.build();
    }

    private static AgentCard card(URI uri) {
        return AgentCard.builder()
                .name("test")
                .description("test agent")
                .version("1.0")
                .url(uri.toString())
                .preferredTransport("JSONRPC")
                .capabilities(AgentCapabilities.builder()
                        .streaming(false).pushNotifications(false).build())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", uri.toString())))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .build();
    }

    private static final class RecordingFactory implements A2aConnectionManager.ClientFactory {
        private final List<Long> caps = new ArrayList<>();

        @Override
        public Client create(AgentCard agentCard, long maxResponseBytes)
                throws A2AClientException {
            caps.add(maxResponseBytes);
            return Client.builder(agentCard)
                    .clientConfig(ClientConfig.builder()
                            .setStreaming(false).setUseClientPreference(true).build())
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
                    .build();
        }
    }
}
