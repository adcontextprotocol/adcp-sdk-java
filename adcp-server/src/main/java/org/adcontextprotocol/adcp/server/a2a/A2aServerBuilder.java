package org.adcontextprotocol.adcp.server.a2a;

import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.adcontextprotocol.adcp.error.ConfigurationError;
import org.adcontextprotocol.adcp.server.AdcpPlatform;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Builds A2A server-side request handling backed by an {@link AdcpPlatform}.
 *
 * <p><b>Authentication:</b> This builder produces a {@link DefaultRequestHandler} that
 * is then wrapped in an {@link A2aServlet}. Authentication is configured on the servlet,
 * not here. Use {@link A2aServlet#A2aServlet(DefaultRequestHandler, A2aAuthProvider)} to
 * wire a real {@link A2aAuthProvider} before deploying to production.
 *
 * <p><b>In-memory stores:</b> {@link #build()} creates in-memory task and queue stores
 * that are <strong>unbounded and non-persistent</strong>. They are suitable for local
 * development and testing only. Production deployments should configure external,
 * bounded task storage to prevent memory exhaustion under sustained load.
 */
public final class A2aServerBuilder {

    private final AdcpPlatform platform;
    private String agentName;
    private String agentUrl;
    private String agentVersion;
    private @Nullable AgentCard builtCard;

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
        mainEventBusProcessor.ensureStarted();

        return DefaultRequestHandler.create(
                new A2aAgentExecutor(platform),
                taskStore,
                queueManager,
                pushConfigStore,
                mainEventBusProcessor,
                Runnable::run,
                Runnable::run);
    }

    public AgentCard buildAgentCard() {
        require(agentName, "agentName");
        require(agentUrl, "agentUrl");
        require(agentVersion, "agentVersion");
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
                .skills(List.of())
                .build();
    }

    /** Returns the AgentCard built by this builder after {@link #build()} is called. */
    public AgentCard getAgentCard() {
        if (builtCard == null) {
            throw new IllegalStateException("Call build() before getAgentCard()");
        }
        return builtCard;
    }

    private void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ConfigurationError("A2aServerBuilder." + field + " is required", field);
        }
    }
}
