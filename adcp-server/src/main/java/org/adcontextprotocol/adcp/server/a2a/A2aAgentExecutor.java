package org.adcontextprotocol.adcp.server.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.adcontextprotocol.adcp.AdcpVersion;
import org.adcontextprotocol.adcp.error.AdcpError;
import org.adcontextprotocol.adcp.error.VersionUnsupportedError;
import org.adcontextprotocol.adcp.schema.AdcpObjectMapperFactory;
import org.adcontextprotocol.adcp.server.AdcpContext;
import org.adcontextprotocol.adcp.server.AdcpPlatform;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts A2A message requests to {@link AdcpPlatform#handleTool}.
 */
public final class A2aAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(A2aAgentExecutor.class);
    private static final String TOOL_NAME_KEY = "adcp_tool_name";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    private static final int MAX_PARTS_SCAN = 20;

    private final AdcpPlatform platform;
    private final ObjectMapper objectMapper;

    public A2aAgentExecutor(AdcpPlatform platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.objectMapper = AdcpObjectMapperFactory.create();
    }

    @Override
    public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        Message message = ctx.getMessage();
        String toolName = extractToolName(message);
        Map<String, Object> args = extractArgs(message);
        AdcpVersion version = extractVersion(args);
        args.remove("adcp_major_version");
        args.remove("adcp_version");

        try {
            emitter.startWork();
            String rawMessageId = message != null ? message.messageId() : ctx.getTaskId();
            String safeMessageId = rawMessageId == null ? null
                    : (rawMessageId.length() > 128 ? rawMessageId.substring(0, 128) : rawMessageId)
                            .replaceAll("[\\p{Cc}]", "");
            Object response = platform.handleTool(toolName, args,
                    new AdcpContext(version, extractCallContextHeaders(ctx), safeMessageId));
            emitter.sendMessage(List.of(new TextPart(objectMapper.writeValueAsString(response))));
            emitter.complete();
        } catch (AdcpError e) {
            log.warn("A2A tool call failed ({}) [{}]: {}", toolName, e.code(),
                    sanitizeErrorMessage(e.getMessage()));
            emitter.fail(errorMessage(e.code(), sanitizeErrorMessage(e.getMessage())));
        } catch (Exception e) {
            log.error("A2A tool call failed: {}", toolName, e);
            emitter.fail(errorMessage("internal_error", "internal error"));
        }
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
        emitter.cancel();
    }

    private String extractToolName(@Nullable Message message) {
        if (message == null) {
            throw new InvalidRequestError("A2A request was missing a message payload");
        }
        if (message.metadata() != null && message.metadata().get(TOOL_NAME_KEY) instanceof String toolName
                && !toolName.isBlank()) {
            String capped = toolName.length() > 256 ? toolName.substring(0, 256) : toolName;
            return capped.replaceAll("[\\p{Cc}]", "");
        }
        if (message.parts() != null) {
            int limit = Math.min(message.parts().size(), MAX_PARTS_SCAN);
            for (int i = 0; i < limit; i++) {
                Part<?> part = message.parts().get(i);
                if (part instanceof TextPart textPart && textPart.text() != null && !textPart.text().isBlank()) {
                    String name = textPart.text();
                    String capped = name.length() > 256 ? name.substring(0, 256) : name;
                    return capped.replaceAll("[\\p{Cc}]", "");
                }
            }
        }
        throw new InvalidRequestError("A2A request did not specify an AdCP tool name");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractArgs(@Nullable Message message) {
        if (message == null) {
            return new LinkedHashMap<>();
        }
        if (message.metadata() != null && message.metadata().get("adcp_args") != null) {
            return objectMapper.convertValue(message.metadata().get("adcp_args"), LinkedHashMap.class);
        }
        if (message.parts() != null) {
            int limit = Math.min(message.parts().size(), MAX_PARTS_SCAN);
            for (int i = 0; i < limit; i++) {
                Part<?> part = message.parts().get(i);
                if (part instanceof DataPart dataPart && dataPart.data() != null) {
                    return objectMapper.convertValue(dataPart.data(), LinkedHashMap.class);
                }
            }
        }
        return new LinkedHashMap<>();
    }

    private Message errorMessage(String code, String message) {
        try {
            return Message.builder()
                    .role(Message.Role.ROLE_AGENT)
                    .parts(new TextPart(objectMapper.writeValueAsString(
                            Map.of("error", code, "message", message))))
                    .build();
        } catch (Exception ignored) {
            return Message.builder()
                    .role(Message.Role.ROLE_AGENT)
                    .parts(new TextPart("{\"error\":\"internal_error\"}"))
                    .build();
        }
    }

    private @Nullable AdcpVersion extractVersion(Map<String, Object> args) {
        Object majorRaw = args.get("adcp_major_version");
        int major;
        if (majorRaw instanceof Number num) {
            major = num.intValue();
        } else if (majorRaw instanceof String s) {
            try {
                major = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        if (major < 1 || major > 99) {
            throw new VersionUnsupportedError(null,
                    "Unsupported AdCP major version: " + major, String.valueOf(major), null);
        }
        if (major < 3) {
            return new AdcpVersion(major, null);
        }
        String minor = args.get("adcp_version") instanceof String s ? s : null;
        if (minor != null && minor.length() > 20) {
            log.warn("Rejecting oversized adcp_version field ({} chars)", minor.length());
            minor = null;
        }
        if (minor != null) {
            minor = minor.replaceAll("[\\p{Cc}]", "");
            if (minor.isBlank()) {
                minor = null;
            }
        }
        return new AdcpVersion(major, minor);
    }

    private static Map<String, String> extractCallContextHeaders(RequestContext ctx) {
        var callContext = ctx.getCallContext();
        if (callContext == null || callContext.getState() == null || callContext.getState().isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        callContext.getState().forEach((key, value) -> {
            if (key != null && value != null
                    && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
                headers.put(key, String.valueOf(value));
            } else if (key != null && value != null) {
                log.debug("Skipping non-primitive ServerCallContext state entry: {}", key);
            }
        });
        return headers;
    }

    private static String sanitizeErrorMessage(String raw) {
        if (raw == null) {
            return "(no error detail)";
        }
        String truncated = raw.length() > MAX_ERROR_MESSAGE_LENGTH
                ? raw.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..."
                : raw;
        return truncated.replaceAll("[\\p{Cc}]", "");
    }
}
