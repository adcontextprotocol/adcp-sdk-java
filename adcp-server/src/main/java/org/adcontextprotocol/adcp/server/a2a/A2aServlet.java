package org.adcontextprotocol.adcp.server.a2a;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.util.sse.SseFormatter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.StreamingEventKind;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal Jakarta servlet bridge for A2A JSON-RPC requests.
 *
 * <p><b>Authentication:</b> Use {@link #A2aServlet(RequestHandler, A2aAuthProvider)} to
 * wire a real {@link A2aAuthProvider}. The single-argument constructor processes every
 * request as unauthenticated and must not be used in production deployments.
 *
 * <p>Streaming responses require servlet async support; deploy this servlet with
 * {@code asyncSupported=true}.
 */
public final class A2aServlet extends HttpServlet {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private static final int MAX_REQUEST_BYTES = 1 * 1024 * 1024; // 1 MB
    private static final int MAX_METHOD_LENGTH = 128;
    private static final int SSE_PREFETCH = 8;
    private static final long SSE_STREAM_TIMEOUT_SECONDS = 300;

    private final transient RequestHandler handler;
    private final transient A2aAuthProvider authProvider;

    /**
     * Creates a servlet with the given auth provider.
     * Use this constructor for production deployments.
     */
    public A2aServlet(RequestHandler handler, A2aAuthProvider authProvider) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.authProvider = Objects.requireNonNull(authProvider, "authProvider");
    }

    /**
     * Creates a servlet that accepts all requests as <strong>unauthenticated</strong>.
     *
     * <p><b>WARNING:</b> This constructor is intended for testing and local development
     * only. Any caller that can reach this endpoint can invoke all registered tools
     * without authentication. Use {@link #A2aServlet(RequestHandler, A2aAuthProvider)}
     * with a real {@link A2aAuthProvider} for production deployments.
     *
     * @deprecated Use {@link #A2aServlet(RequestHandler, A2aAuthProvider)} for production.
     */
    @Deprecated
    public A2aServlet(RequestHandler handler) {
        this(handler, request -> new ServerCallContext(
                UnauthenticatedUser.INSTANCE,
                Map.of(),
                Set.of(),
                AgentInterface.CURRENT_PROTOCOL_VERSION));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object requestId = null;
        try {
            String body = readRequestBody(request.getInputStream());
            var parsedBody = JsonParser.parseString(body);
            if (!parsedBody.isJsonObject()) {
                throw new JsonParseException("JSON-RPC request must be an object");
            }
            JsonObject envelope = parsedBody.getAsJsonObject();
            requestId = extractId(envelope.get("id"));
            JsonElement methodElement = envelope.get("method");
            String method = methodElement != null && methodElement.isJsonPrimitive()
                    ? methodElement.getAsString()
                    : null;
            if (method == null || method.isBlank()) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, requestId,
                        new InvalidRequestError("JSON-RPC method is required"));
                return;
            }
            if (method.length() > MAX_METHOD_LENGTH) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, requestId,
                        new InvalidRequestError("JSON-RPC method too long"));
                return;
            }

            ServerCallContext callContext = authProvider.authenticate(request);

            switch (method) {
                case A2AMethods.SEND_MESSAGE_METHOD -> {
                    SendMessageRequest parsed = JsonUtil.fromJson(body, SendMessageRequest.class);
                    if (wantsStreaming(request)) {
                        stream(request, response, requestId,
                                handler.onMessageSendStream(parsed.getParams(), callContext));
                    } else {
                        writeJson(response, HttpServletResponse.SC_OK,
                                new SendMessageResponse(requestId,
                                        handler.onMessageSend(parsed.getParams(), callContext)));
                    }
                }
                case A2AMethods.SEND_STREAMING_MESSAGE_METHOD -> {
                    SendStreamingMessageRequest parsed = JsonUtil.fromJson(body, SendStreamingMessageRequest.class);
                    stream(request, response, requestId,
                            handler.onMessageSendStream(parsed.getParams(), callContext));
                }
                case A2AMethods.GET_TASK_METHOD -> {
                    GetTaskRequest parsed = JsonUtil.fromJson(body, GetTaskRequest.class);
                    writeJson(response, HttpServletResponse.SC_OK,
                            new GetTaskResponse(requestId,
                                    handler.onGetTask(parsed.getParams(), callContext)));
                }
                case A2AMethods.CANCEL_TASK_METHOD -> {
                    CancelTaskRequest parsed = JsonUtil.fromJson(body, CancelTaskRequest.class);
                    writeJson(response, HttpServletResponse.SC_OK,
                            new CancelTaskResponse(requestId,
                                    handler.onCancelTask(parsed.getParams(), callContext)));
                }
                case A2AMethods.SUBSCRIBE_TO_TASK_METHOD -> {
                    SubscribeToTaskRequest parsed = JsonUtil.fromJson(body, SubscribeToTaskRequest.class);
                    stream(request, response, requestId,
                            handler.onSubscribeToTask(parsed.getParams(), callContext));
                }
                default -> writeError(response, HttpServletResponse.SC_BAD_REQUEST, requestId,
                        new InvalidRequestError("Unsupported JSON-RPC method: "
                                + sanitizeMethodName(method)));
            }
        } catch (JsonParseException | JsonProcessingException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, requestId,
                    new InvalidRequestError("Invalid JSON-RPC request"));
        } catch (IOException e) {
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, requestId,
                        new InvalidRequestError("Invalid request body"));
                return;
            }
            throw e;
        } catch (A2AError e) {
            writeError(response, HttpServletResponse.SC_OK, requestId, e);
        } catch (Exception e) {
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, requestId,
                    new InternalError("Internal error"));
        }
    }

    private static boolean wantsStreaming(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream");
    }

    private static String readRequestBody(InputStream inputStream) throws IOException {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BYTES) {
                    throw new IOException("A2A request body exceeds " + MAX_REQUEST_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static Object extractId(JsonElement idElement) {
        if (idElement == null || idElement.isJsonNull()) {
            return null;
        }
        if (idElement.isJsonPrimitive()) {
            if (idElement.getAsJsonPrimitive().isString()) {
                String s = idElement.getAsString();
                return s.length() > 128 ? s.substring(0, 128) : s;
            }
            if (idElement.getAsJsonPrimitive().isNumber()) {
                return idElement.getAsNumber();
            }
            // Boolean ids are non-conforming per JSON-RPC 2.0; treat as null
        }
        // Structured ids (arrays, objects) are non-conforming; do not echo
        return null;
    }

    private static String sanitizeMethodName(String method) {
        if (method == null) return "(null)";
        String truncated = method.length() > MAX_METHOD_LENGTH
                ? method.substring(0, MAX_METHOD_LENGTH) + "..."
                : method;
        return truncated.replaceAll("[\\p{Cc}]", "");
    }

    private static void writeJson(HttpServletResponse response, int status, Object payload) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        try {
            response.getWriter().write(JsonUtil.toJson(payload));
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to serialize JSON-RPC response", e);
        }
        response.getWriter().flush();
    }

    private static void writeError(HttpServletResponse response, int status,
                                   Object requestId, A2AError error) throws IOException {
        writeJson(response, status, new A2AErrorResponse(requestId, error));
    }

    private static void stream(HttpServletRequest request, HttpServletResponse response, Object requestId,
                               Flow.Publisher<StreamingEventKind> publisher) throws IOException {
        if (!request.isAsyncSupported()) {
            throw new IllegalStateException("A2aServlet requires asyncSupported=true for streaming responses");
        }
        AsyncContext asyncContext = request.startAsync(request, response);
        asyncContext.setTimeout(SSE_STREAM_TIMEOUT_SECONDS * 1000L);

        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-cache");

        AtomicLong sequence = new AtomicLong(1);
        AtomicReference<Flow.Subscription> subRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        Object writerLock = new Object();

        asyncContext.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                cancelSubscription(subRef);
                try {
                    writeTimeoutResponse(response, requestId, sequence, asyncContext, writerLock, completed);
                } catch (IOException ignored) {
                    completeAsync(asyncContext, writerLock, completed);
                }
            }

            @Override
            public void onError(AsyncEvent event) {
                cancelSubscription(subRef);
                try {
                    writeFinalStreamingResponse(response,
                            new SendStreamingMessageResponse(requestId, toA2aError(event.getThrowable())),
                            sequence, asyncContext, writerLock, completed);
                } catch (IOException ignored) {
                    completeAsync(asyncContext, writerLock, completed);
                }
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
            }
        });

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subRef.set(subscription);
                subscription.request(SSE_PREFETCH);
            }

            @Override
            public void onNext(StreamingEventKind item) {
                try {
                    writeStreamingResponse(response, new SendStreamingMessageResponse(requestId, item),
                            sequence, writerLock, completed);
                    Flow.Subscription subscription = subRef.get();
                    if (subscription != null && !completed.get()) {
                        subscription.request(1);
                    }
                } catch (IOException e) {
                    cancelSubscription(subRef);
                    completeAsync(asyncContext, writerLock, completed);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                try {
                    writeFinalStreamingResponse(response,
                            new SendStreamingMessageResponse(requestId, toA2aError(throwable)),
                            sequence, asyncContext, writerLock, completed);
                } catch (IOException ignored) {
                    completeAsync(asyncContext, writerLock, completed);
                }
            }

            @Override
            public void onComplete() {
                completeAsync(asyncContext, writerLock, completed);
            }
        });
    }

    private static void writeTimeoutResponse(HttpServletResponse response, Object requestId,
                                             AtomicLong sequence, AsyncContext asyncContext, Object writerLock,
                                             AtomicBoolean completed) throws IOException {
        // Both the committed and uncommitted cases are handled atomically under a single lock
        // acquisition to prevent onNext() from writing another SSE event between the timeout
        // decision and the final timeout event / completion.
        synchronized (writerLock) {
            if (completed.get()) {
                return;
            }
            completed.set(true);
            if (!response.isCommitted()) {
                writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, requestId,
                        new InternalError("Streaming response timed out"));
            } else {
                response.getWriter().write(SseFormatter.formatResponseAsSSE(
                        new SendStreamingMessageResponse(requestId, new InternalError("Streaming response timed out")),
                        sequence.getAndIncrement()));
                response.getWriter().flush();
            }
            asyncContext.complete();
        }
    }

    private static void writeFinalStreamingResponse(HttpServletResponse response, SendStreamingMessageResponse payload,
                                                     AtomicLong sequence, AsyncContext asyncContext,
                                                     Object writerLock, AtomicBoolean completed) throws IOException {
        synchronized (writerLock) {
            if (completed.compareAndSet(false, true)) {
                response.getWriter().write(SseFormatter.formatResponseAsSSE(payload, sequence.getAndIncrement()));
                response.getWriter().flush();
                asyncContext.complete();
            }
        }
    }

    private static void writeStreamingResponse(HttpServletResponse response, SendStreamingMessageResponse payload,
                                               AtomicLong sequence, Object writerLock,
                                               AtomicBoolean completed) throws IOException {
        synchronized (writerLock) {
            if (completed.get()) {
                return;
            }
            response.getWriter().write(SseFormatter.formatResponseAsSSE(payload, sequence.getAndIncrement()));
            response.getWriter().flush();
        }
    }

    private static void cancelSubscription(AtomicReference<Flow.Subscription> subRef) {
        Flow.Subscription subscription = subRef.getAndSet(null);
        if (subscription != null) {
            subscription.cancel();
        }
    }

    private static void completeAsync(AsyncContext asyncContext, Object writerLock, AtomicBoolean completed) {
        synchronized (writerLock) {
            if (completed.compareAndSet(false, true)) {
                asyncContext.complete();
            }
        }
    }

    private static A2AError toA2aError(Throwable throwable) {
        return throwable instanceof A2AError a2aError
                ? a2aError
                : new InternalError("Internal error");
    }
}
