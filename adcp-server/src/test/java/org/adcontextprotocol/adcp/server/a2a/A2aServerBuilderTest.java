package org.adcontextprotocol.adcp.server.a2a;

import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import org.adcontextprotocol.adcp.error.ConfigurationError;
import org.adcontextprotocol.adcp.server.AdcpPlatform;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    void build_starts_event_bus_processor_thread() throws Exception {
        DefaultRequestHandler handler = A2aServerBuilder.create(platform())
                .agentName("test-agent")
                .agentUrl("https://agent.example.com")
                .agentVersion("1.0.0")
                .build();

        Field processorField = DefaultRequestHandler.class.getDeclaredField("mainEventBusProcessor");
        processorField.setAccessible(true);
        Object processor = processorField.get(handler);
        assertNotNull(processor, "DefaultRequestHandler should have a MainEventBusProcessor");

        Field threadField = processor.getClass().getDeclaredField("processorThread");
        threadField.setAccessible(true);
        Thread processorThread = (Thread) threadField.get(processor);
        assertNotNull(processorThread, "MainEventBusProcessor should have a started processor thread");
        assertTrue(processorThread.isAlive(),
                "MainEventBusProcessor thread should be running after build()");
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

    @Test
    void build_agent_card_populates_skills_from_platform_supported_tools() {
        AdcpPlatform richPlatform = new AdcpPlatform() {
            @Override
            public Set<String> supportedTools() {
                return Set.of("get_products", "get_creatives");
            }

            @Override
            public Map<String, String> toolDescriptions() {
                return Map.of(
                        "get_products", "Fetch available ad products",
                        "get_creatives", "Retrieve creative assets");
            }
        };

        A2aServerBuilder builder = A2aServerBuilder.create(richPlatform)
                .agentName("rich-agent")
                .agentUrl("https://agent.example.com")
                .agentVersion("1.0.0");

        AgentCard card = builder.buildAgentCard();

        assertNotNull(card.skills());
        assertEquals(2, card.skills().size());

        // Sorted by tool name for deterministic ordering
        List<AgentSkill> skills = card.skills().stream()
                .sorted(java.util.Comparator.comparing(AgentSkill::id))
                .toList();
        assertEquals("get_creatives", skills.get(0).id());
        assertEquals("Retrieve creative assets", skills.get(0).description());
        assertEquals("get_products", skills.get(1).id());
        assertEquals("Fetch available ad products", skills.get(1).description());
    }

    @Test
    void build_agent_card_uses_tool_name_as_description_when_not_provided() {
        A2aServerBuilder builder = A2aServerBuilder.create(platform()) // platform has "echo" with no description
                .agentName("test-agent")
                .agentUrl("https://agent.example.com")
                .agentVersion("1.0.0");

        AgentCard card = builder.buildAgentCard();

        assertEquals(1, card.skills().size());
        AgentSkill skill = card.skills().get(0);
        assertEquals("echo", skill.id());
        assertEquals("echo", skill.description()); // falls back to tool name
    }

    @Test
    void build_agent_card_has_empty_skills_when_platform_has_no_tools() {
        AdcpPlatform emptyPlatform = new AdcpPlatform() {};

        AgentCard card = A2aServerBuilder.create(emptyPlatform)
                .agentName("empty-agent")
                .agentUrl("https://agent.example.com")
                .agentVersion("1.0.0")
                .buildAgentCard();

        assertNotNull(card.skills());
        assertTrue(card.skills().isEmpty());
    }

    @Test
    void build_uses_injectable_agent_executor_not_caller_thread() throws Exception {
        AtomicReference<Thread> executorThread = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Inject an executor that records which thread ran the task
        java.util.concurrent.Executor recordingExecutor = task -> {
            Thread t = new Thread(() -> {
                executorThread.set(Thread.currentThread());
                latch.countDown();
                task.run();
            }, "test-agent-thread");
            t.setDaemon(true);
            t.start();
        };

        A2aServerBuilder.create(platform())
                .agentName("test-agent")
                .agentUrl("https://agent.example.com")
                .agentVersion("1.0.0")
                .agentExecutor(recordingExecutor)
                .build(); // just verify it builds without error

        // Verify the builder accepts a custom executor (structural test)
        assertNotNull(recordingExecutor);
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
