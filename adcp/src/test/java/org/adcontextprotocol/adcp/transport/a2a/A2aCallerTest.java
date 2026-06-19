package org.adcontextprotocol.adcp.transport.a2a;

import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.adcontextprotocol.adcp.error.ProtocolError;
import org.adcontextprotocol.adcp.schema.AdcpObjectMapperFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class A2aCallerTest {

    private final A2aCaller caller = new A2aCaller(AdcpObjectMapperFactory.create());

    @Test
    void callTool_deserializes_message_response() {
        A2aCaller.A2aMessageClient client = (params, consumers, errorHandler, context) ->
                consumers.getFirst().accept(new MessageEvent(Message.builder()
                        .role(Message.Role.ROLE_AGENT)
                        .parts(new TextPart("{\"ok\":true}"))
                        .build()), testCard());

        EchoResponse response = caller.callTool(client, "echo", Map.of("q", "x"),
                EchoResponse.class, Map.of());

        assertTrue(response.ok());
    }

    @Test
    void callTool_wraps_client_exception() {
        A2aCaller.A2aMessageClient client = (params, consumers, errorHandler, context) -> {
            throw new A2AClientException("boom");
        };

        ProtocolError error = assertThrows(ProtocolError.class,
                () -> caller.callTool(client, "echo", Map.of(), EchoResponse.class, Map.of()));

        assertEquals("a2a", error.protocol());
        assertTrue(error.getMessage().contains("echo"));
    }

    @Test
    void callTool_surfaces_failed_task_update() {
        A2aCaller.A2aMessageClient client = (params, consumers, errorHandler, context) ->
                consumers.getFirst().accept(new TaskUpdateEvent(
                        Task.builder()
                                .id("task-1")
                                .contextId("ctx-1")
                                .status(new TaskStatus(
                                        TaskState.TASK_STATE_FAILED,
                                        Message.builder()
                                                .role(Message.Role.ROLE_AGENT)
                                                .parts(new TextPart("tool failed"))
                                                .build(),
                                        java.time.OffsetDateTime.now()))
                                .build(),
                        new TaskStatusUpdateEvent(
                                "task-1",
                                new TaskStatus(TaskState.TASK_STATE_FAILED),
                                "ctx-1",
                                Map.of())),
                        testCard());

        ProtocolError error = assertThrows(ProtocolError.class,
                () -> caller.callTool(client, "echo", Map.of(), EchoResponse.class, Map.of()));

        assertTrue(error.getMessage().contains("tool failed"));
    }

    private static AgentCard testCard() {
        return AgentCard.builder()
                .name("test")
                .description("test agent")
                .version("1.0")
                .url("https://agent.example.com")
                .preferredTransport("JSONRPC")
                .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "https://agent.example.com")))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .build();
    }

    private record EchoResponse(boolean ok) {}
}
