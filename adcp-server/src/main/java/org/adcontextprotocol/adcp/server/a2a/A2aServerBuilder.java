package org.adcontextprotocol.adcp.server.a2a;

import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.adcontextprotocol.adcp.error.ConfigurationError;
import org.adcontextprotocol.adcp.server.AdcpPlatform;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Builds A2A server-side request handling backed by an {@link AdcpPlatform}.
 *
 * <p><b>Authentication:</b> This builder produces a {@link DefaultRequestHandler} that
 * is then wrapped in an {@link A2aServlet}. Authentication is configured on the servlet,
 * not here. Use {@link A2aServlet#A2aServlet(RequestHandler, A2aAuthProvider)} to
 * wire a real {@link A2aAuthProvider} before deploying to production.
 *
 * <p><b>In-memory stores:</b> {@link #build()} creates in-memory task and queue stores
 * that are <strong>unbounded and non-persistent</strong>. They are suitable for local
 * development and testing only. Production deployments should configure external,
 * bounded task storage to prevent memory exhaustion under sustained load.
 */
public final class A2aServerBuilder {

    /**
     * Default executor: spawns one virtual thread per task. No lifecycle management needed —
     * each virtual thread is created on demand and terminates when its task completes.
     * Using a plain lambda avoids the {@link java.util.concurrent.ExecutorService} resource
     * that {@link java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()} returns
     * and would need to be shut down.
     */
    private static final Executor VIRTUAL_THREAD_EXECUTOR =
            task -> Thread.ofVirtual().start(task);

    private final AdcpPlatform platform;
    private @Nullable String agentName;
    private @Nullable String agentUrl;
    private @Nullable String agentVersion;
    private @Nullable AgentCard builtCard;
    private @Nullable Executor agentExecutor;
    private @Nullable Executor eventConsumerExecutor;

    private A2aServerBuilder(AdcpPlatform platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public static A2aServerBuilder create(AdcpPlatform platform) {
        return new A2aServerBuilder(platform);
    }

    public A2aServerBuilder agentName(String agentName) {
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        return this;
    }

    public A2aServerBuilder agentUrl(String agentUrl) {
        this.agentUrl = Objects.requireNonNull(agentUrl, "agentUrl");
        return this;
    }

    public A2aServerBuilder agentVersion(String agentVersion) {
        this.agentVersion = Objects.requireNonNull(agentVersion, "agentVersion");
        return this;
    }

    /**
     * Sets the executor used for agent execution (the {@link A2aAgentExecutor} call).
     * Defaults to a virtual-thread-per-task executor.
     *
     * <p>Inject a custom executor in tests to control execution order or assert
     * that work is dispatched off the caller thread.
     */
    public A2aServerBuilder agentExecutor(Executor agentExecutor) {
        this.agentExecutor = Objects.requireNonNull(agentExecutor, "agentExecutor");
        return this;
    }

    /**
     * Sets the executor used for SSE event consumption.
     * Defaults to a virtual-thread-per-task executor.
     */
    public A2aServerBuilder eventConsumerExecutor(Executor eventConsumerExecutor) {
        this.eventConsumerExecutor = Objects.requireNonNull(eventConsumerExecutor, "eventConsumerExecutor");
        return this;
    }

    public DefaultRequestHandler build() {
        this.builtCard = buildAgentCard();

        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        MainEventBus mainEventBus = new MainEventBus();
        InMemoryQueueManager queueManager = new InMemoryQueueManager(taskStore, mainEventBus);
        InMemoryPushNotificationConfigStore pushConfigStore = new InMemoryPushNotificationConfigStore();
        MainEventBusProcessor mainEventBusProcessor = new MainEventBusProcessor(
                mainEventBus,
                taskStore,
                (event, snapshot) -> { },
                queueManager);
        startEventBusProcessor(mainEventBusProcessor);

        // Use virtual-thread-per-task executors by default so agent execution and SSE event
        // consumption run off the caller thread. This prevents the streaming response from
        // being delayed or blocked while the SSE stream is being established.
        Executor resolvedAgentExecutor =
                agentExecutor != null ? agentExecutor : VIRTUAL_THREAD_EXECUTOR;
        Executor resolvedEventExecutor =
                eventConsumerExecutor != null ? eventConsumerExecutor : VIRTUAL_THREAD_EXECUTOR;

        return DefaultRequestHandler.create(
                new A2aAgentExecutor(platform),
                taskStore,
                queueManager,
                pushConfigStore,
                mainEventBusProcessor,
                resolvedAgentExecutor,
                resolvedEventExecutor);
    }

    public AgentCard buildAgentCard() {
        require(agentName, "agentName");
        require(agentUrl, "agentUrl");
        require(agentVersion, "agentVersion");

        Map<String, String> descriptions = platform.toolDescriptions();
        List<AgentSkill> skills = new ArrayList<>();
        // Sort for stable, deterministic card output across JVM runs
        platform.supportedTools().stream().sorted().forEach(toolName -> {
            String description = descriptions.getOrDefault(toolName, toolName);
            skills.add(AgentSkill.builder()
                    .id(toolName)
                    .name(toolName)
                    .description(description)
                    .tags(List.of())
                    .examples(List.of())
                    .inputModes(List.of("text"))
                    .outputModes(List.of("text"))
                    .build());
        });

        return AgentCard.builder()
                .name(agentName)
                .description("AdCP A2A agent")
                .version(agentVersion)
                .url(agentUrl)
                .preferredTransport("JSONRPC")
                .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", agentUrl)))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(skills)
                .build();
    }

    /** Returns the AgentCard built by this builder after {@link #build()} is called. */
    public AgentCard getAgentCard() {
        if (builtCard == null) {
            throw new IllegalStateException("Call build() before getAgentCard()");
        }
        return builtCard;
    }

    /**
     * Starts the {@link MainEventBusProcessor} event distribution thread.
     *
     * <p>In the pinned A2A SDK (1.0.0.CR1), {@link MainEventBusProcessor#ensureStarted()}
     * is a no-op and the real {@code start()} method is package-private. This method uses
     * reflection to invoke {@code start()} so the processor thread actually distributes
     * events. When the SDK exposes a public start API, this reflection shim should be
     * replaced with a direct call.
     */
    private static void startEventBusProcessor(MainEventBusProcessor processor) {
        try {
            Method startMethod = MainEventBusProcessor.class.getDeclaredMethod("start");
            startMethod.setAccessible(true);
            startMethod.invoke(processor);
        } catch (ReflectiveOperationException e) {
            throw new ConfigurationError(
                    "Failed to start MainEventBusProcessor via reflection: " + e.getMessage(),
                    "mainEventBusProcessor");
        }
    }

    private void require(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ConfigurationError("A2aServerBuilder." + field + " is required", field);
        }
    }
}
