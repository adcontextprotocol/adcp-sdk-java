package org.adcontextprotocol.adcp.transport.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.adcontextprotocol.adcp.error.ProtocolError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Calls AdCP tools over A2A JSON-RPC + SSE.
 */
public final class A2aCaller {

    private static final Logger log = LoggerFactory.getLogger(A2aCaller.class);
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;
    private static final int MAX_ERROR_LENGTH = 500;
    private static final long RESPONSE_TIMEOUT_SECONDS = 30;
    private static final String TOOL_NAME_KEY = "adcp_tool_name";
    private static final int MAX_HISTORY_SCAN = 20;
    private static final int MAX_PARTS_SCAN = 20;

    private static final int MAX_TOOL_NAME_LENGTH = 256;

    private final ObjectMapper objectMapper;

    public A2aCaller(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
        this.objectMapper.deactivateDefaultTyping();
    }

    public <T> T callTool(Client client, String toolName,
                          Map<String, Object> args, Class<T> responseType) {
        return callTool(new ClientAdapter(client), toolName, args, responseType, Map.of());
    }

    public <T> T callTool(Client client, String toolName,
                          Map<String, Object> args, Class<T> responseType,
                          Map<String, String> headers) {
        for (var entry : headers.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue();
            if (k == null || k.indexOf('\r') >= 0 || k.indexOf('\n') >= 0
                    || v == null || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(
                        "callTool headers must not contain CR/LF or null: " + k);
            }
        }
        return callTool(new ClientAdapter(client), toolName, args, responseType, headers);
    }

    <T> T callTool(A2aMessageClient client, String toolName,
                   Map<String, Object> args, Class<T> responseType,
                   Map<String, String> headers) {
        // Validate toolName before use — reject rather than silently mutate the outbound request
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be null or blank");
        }
        if (toolName.length() > MAX_TOOL_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "toolName exceeds max length of " + MAX_TOOL_NAME_LENGTH + ": " + toolName.length());
        }
        if (toolName.chars().anyMatch(c -> Character.isISOControl(c) && c != '\t')) {
            throw new IllegalArgumentException("toolName must not contain control characters");
        }
        // Sanitized copy used only in log/error strings — the original is sent on the wire
        final String safeToolName = toolName.replaceAll("[\\p{Cc}]", "");

        CountDownLatch completion = new CountDownLatch(1);
        AtomicReference<Message> latestMessage = new AtomicReference<>();
        AtomicReference<Task> latestTask = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        List<BiConsumer<ClientEvent, org.a2aproject.sdk.spec.AgentCard>> consumers = List.of((event, card) -> {
            if (event instanceof MessageEvent messageEvent) {
                latestMessage.set(messageEvent.getMessage());
                completion.countDown();
            } else if (event instanceof TaskEvent taskEvent) {
                latestTask.set(taskEvent.getTask());
                if (isTerminal(taskEvent.getTask())) {
                    completion.countDown();
                }
            } else if (event instanceof TaskUpdateEvent taskUpdateEvent) {
                latestTask.set(taskUpdateEvent.getTask());
                if (isTerminal(taskUpdateEvent.getTask())) {
                    completion.countDown();
                }
            }
        });
        Consumer<Throwable> errorHandler = throwable -> {
            if (throwable != null) {
                failure.compareAndSet(null, throwable);
                completion.countDown();
            }
        };

        try {
            client.sendMessage(buildRequest(toolName, args), consumers, errorHandler,
                    new ClientCallContext(Map.of(), headers));

            // Guard for synchronous-delivery clients that invoke callbacks inline
            // before sendMessage() returns; the latch is already at 0 in that case
            // so this countDown() is a no-op — but we ensure we don't await forever
            // if the client is synchronous and never fires the error handler.
            if (completion.getCount() > 0
                    && (latestMessage.get() != null || latestTask.get() != null || failure.get() != null)) {
                completion.countDown();
            }

            if (!completion.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new ProtocolError("a2a",
                        "Timed out waiting for A2A response for " + safeToolName, null);
            }
            if (failure.get() != null) {
                throw wrapFailure(safeToolName, failure.get());
            }
            return extractResponse(latestMessage.get(), latestTask.get(), responseType);
        } catch (ProtocolError e) {
            throw e;
        } catch (A2AClientException e) {
            throw wrapFailure(safeToolName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProtocolError("a2a",
                    "Interrupted while waiting for A2A response for " + safeToolName, e);
        }
    }

    private MessageSendParams buildRequest(String toolName, Map<String, Object> args) {
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .metadata(Map.of(TOOL_NAME_KEY, toolName))
                .parts(new TextPart(toolName), new DataPart(args))
                .build();
        return MessageSendParams.builder()
                .message(message)
                .build();
    }

    private <T> T extractResponse(Message message, Task task, Class<T> responseType) {
        if (message != null) {
            return extractFromParts(message.parts(), responseType);
        }
        if (task != null) {
            if (task.status() != null && task.status().state() == TaskState.TASK_STATE_FAILED) {
                throw new ProtocolError("a2a",
                        "A2A task failed: " + sanitizeErrorText(extractMessageText(task.status().message())),
                        null);
            }
            if (task.status() != null && task.status().state() == TaskState.TASK_STATE_CANCELED) {
                throw new ProtocolError("a2a",
                        "A2A task was canceled: " + sanitizeErrorText(extractMessageText(task.status().message())),
                        null);
            }
            if (task.status() != null && task.status().message() != null) {
                return extractFromParts(task.status().message().parts(), responseType);
            }
            if (task.history() != null && !task.history().isEmpty()) {
                int limit = Math.min(task.history().size(), MAX_HISTORY_SCAN);
                for (int i = task.history().size() - 1; i >= task.history().size() - limit; i--) {
                    Message historyMessage = task.history().get(i);
                    if (historyMessage != null && historyMessage.parts() != null && !historyMessage.parts().isEmpty()) {
                        return extractFromParts(historyMessage.parts(), responseType);
                    }
                }
            }
        }
        throw new ProtocolError("a2a", "Empty response from A2A sendMessage", null);
    }

    private <T> T extractFromParts(List<Part<?>> parts, Class<T> responseType) {
        if (parts == null || parts.isEmpty()) {
            throw new ProtocolError("a2a", "A2A response message had no parts", null);
        }

        int scanLimit = Math.min(parts.size(), MAX_PARTS_SCAN);
        Exception firstParseError = null;
        for (int i = 0; i < scanLimit; i++) {
            Part<?> part = parts.get(i);
            if (part instanceof DataPart dataPart) {
                try {
                    String serialized = objectMapper.writeValueAsString(dataPart.data());
                    if (serialized.length() > MAX_CONTENT_LENGTH) {
                        throw new ProtocolError("a2a",
                                "A2A DataPart response exceeds size limit ("
                                        + serialized.length() + " > " + MAX_CONTENT_LENGTH + ")",
                                null);
                    }
                    return objectMapper.readValue(serialized, responseType);
                } catch (ProtocolError e) {
                    throw e;
                } catch (Exception e) {
                    if (firstParseError == null) {
                        firstParseError = e;
                    }
                    log.debug("Failed to parse A2A DataPart as {}: {}",
                            responseType.getSimpleName(), e.getMessage());
                }
            } else if (part instanceof TextPart textPart) {
                String text = textPart.text();
                if (text == null) {
                    continue;
                }
                if (text.length() > MAX_CONTENT_LENGTH) {
                    throw new ProtocolError("a2a",
                            "A2A response content exceeds size limit ("
                                    + text.length() + " > " + MAX_CONTENT_LENGTH + ")",
                            null);
                }
                try {
                    return objectMapper.readValue(text, responseType);
                } catch (Exception e) {
                    if (firstParseError == null) {
                        firstParseError = e;
                    }
                    log.debug("Failed to parse A2A TextPart as {}: {}",
                            responseType.getSimpleName(), e.getMessage());
                }
            }
        }

        Part<?> first = parts.get(0);
        try {
            JsonNode node = objectMapper.valueToTree(first);
            return objectMapper.treeToValue(node, responseType);
        } catch (Exception e) {
            if (firstParseError != null) {
                e.addSuppressed(firstParseError);
            }
            throw new ProtocolError("a2a",
                    "Cannot deserialize A2A response to " + responseType.getSimpleName(), e);
        }
    }

    private boolean isTerminal(Task task) {
        return task != null && task.status() != null && task.status().state() != null
                && task.status().state().isFinal();
    }

    private ProtocolError wrapFailure(String toolName, Throwable throwable) {
        String message = throwable.getMessage();
        return new ProtocolError("a2a",
                "A2A sendMessage failed for " + toolName + ": " + sanitizeErrorText(message),
                throwable);
    }

    private static String extractMessageText(Message message) {
        if (message == null || message.parts() == null) {
            return "(no error detail)";
        }
        List<String> texts = new ArrayList<>();
        int limit = Math.min(message.parts().size(), MAX_PARTS_SCAN);
        for (int i = 0; i < limit; i++) {
            Part<?> part = message.parts().get(i);
            if (part instanceof TextPart textPart && textPart.text() != null) {
                String text = textPart.text();
                texts.add(text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text);
            }
        }
        return texts.isEmpty() ? "(no error detail)" : String.join("\n", texts);
    }

    private static String sanitizeErrorText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "(no error detail)";
        }
        String truncated = raw.length() > MAX_ERROR_LENGTH
                ? raw.substring(0, MAX_ERROR_LENGTH) + "..."
                : raw;
        return truncated.replaceAll("[\\p{Cc}]", "");
    }

    interface A2aMessageClient {
        void sendMessage(MessageSendParams params,
                         List<BiConsumer<ClientEvent, org.a2aproject.sdk.spec.AgentCard>> consumers,
                         Consumer<Throwable> errorHandler,
                         ClientCallContext context) throws A2AClientException;
    }

    private record ClientAdapter(Client delegate) implements A2aMessageClient {
        @Override
        public void sendMessage(MessageSendParams params,
                                List<BiConsumer<ClientEvent, org.a2aproject.sdk.spec.AgentCard>> consumers,
                                Consumer<Throwable> errorHandler,
                                ClientCallContext context) throws A2AClientException {
            delegate.sendMessage(params, consumers, errorHandler, context);
        }
    }
}
