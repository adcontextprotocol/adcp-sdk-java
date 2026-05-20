package org.adcontextprotocol.adcp.server.a2a;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.events.EventQueue;
import org.a2aproject.sdk.server.events.EventQueueItem;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.adcontextprotocol.adcp.AdcpVersion;
import org.adcontextprotocol.adcp.server.AdcpContext;
import org.adcontextprotocol.adcp.server.AdcpPlatform;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class A2aAgentExecutorTest {

    @Test
    void execute_dispatches_tool_and_emits_response() throws Exception {
        RecordingPlatform platform = new RecordingPlatform();
        A2aAgentExecutor executor = new A2aAgentExecutor(platform);
        RequestContext context = requestContext(Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId("msg-1")
                .metadata(Map.of("adcp_tool_name", "echo"))
                .parts(new TextPart("echo"), new DataPart(Map.of(
                        "adcp_major_version", 3,
                        "adcp_version", "3.1",
                        "query", "test")))
                .build());
        RecordingEmitter emitter = new RecordingEmitter(context);

        executor.execute(context, emitter);

        assertEquals("echo", platform.toolName);
        assertEquals(Map.of("query", "test"), platform.request);
        assertNotNull(platform.context);
        assertEquals(new AdcpVersion(3, "3.1"), platform.context.adcpVersion());
        assertTrue(emitter.started);
        assertTrue(emitter.completed);
        assertNotNull(emitter.messageParts);
        assertEquals("{\"echo\":true}", ((TextPart) emitter.messageParts.getFirst()).text());
    }

    @Test
    void execute_includes_call_context_metadata_in_adcp_context() throws Exception {
        RecordingPlatform platform = new RecordingPlatform();
        A2aAgentExecutor executor = new A2aAgentExecutor(platform);
        RequestContext context = requestContext(
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .metadata(Map.of("adcp_tool_name", "echo"))
                        .parts(new TextPart("echo"))
                        .build(),
                new ServerCallContext(
                        UnauthenticatedUser.INSTANCE,
                        Map.of(
                                "tenant", "acme",
                                "priority", 7,
                                "features", List.of("a", "b")),
                        Set.of(),
                        AgentInterface.CURRENT_PROTOCOL_VERSION));
        RecordingEmitter emitter = new RecordingEmitter(context);

        executor.execute(context, emitter);

        assertEquals(Map.of("tenant", "acme", "priority", "7"), platform.context.headers());
    }

    @Test
    void cancel_delegates_to_emitter_cancel() throws Exception {
        A2aAgentExecutor executor = new A2aAgentExecutor(new RecordingPlatform());
        RequestContext context = requestContext(Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart("echo"))
                .build());
        RecordingEmitter emitter = new RecordingEmitter(context);

        executor.cancel(context, emitter);

        assertTrue(emitter.canceled);
    }

    private static RequestContext requestContext(Message message) throws Exception {
        return requestContext(message, null);
    }

    private static RequestContext requestContext(Message message, ServerCallContext callContext) throws Exception {
        RequestContext.Builder builder = new RequestContext.Builder()
                .setTaskId("task-1")
                .setContextId("ctx-1")
                .setParams(new MessageSendParams(message, null, null));
        if (callContext != null) {
            builder.setServerCallContext(callContext);
        }
        return builder.build();
    }

    private static final class RecordingPlatform extends AdcpPlatform {
        private String toolName;
        private Map<String, Object> request;
        private AdcpContext context;

        @Override
        public Set<String> supportedTools() {
            return Set.of("echo");
        }

        @Override
        public Object handleTool(String toolName, Map<String, Object> request, AdcpContext ctx) {
            this.toolName = toolName;
            this.request = request;
            this.context = ctx;
            return Map.of("echo", true);
        }
    }

    private static final class RecordingEmitter extends AgentEmitter {
        private boolean started;
        private boolean completed;
        private boolean canceled;
        private List<Part<?>> messageParts;

        private RecordingEmitter(RequestContext context) {
            super(context, new NoOpEventQueue());
        }

        @Override
        public void startWork() {
            started = true;
        }

        @Override
        public void sendMessage(List<Part<?>> parts) {
            this.messageParts = parts;
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void cancel() {
            canceled = true;
        }
    }

    private static final class NoOpEventQueue extends EventQueue {
        @Override
        public void awaitQueuePollerStart() {}

        @Override
        public void signalQueuePollerStarted() {}

        @Override
        public void enqueueItem(EventQueueItem item) {}

        @Override
        public EventQueue tap() {
            return this;
        }

        @Override
        public EventQueueItem dequeueEventItem(int timeoutMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public void close() {}

        @Override
        public void close(boolean clear) {}

        @Override
        public void close(boolean clear, boolean interruptPollers) {}
    }
}
