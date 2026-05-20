package org.adcontextprotocol.adcp.server.a2a;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.DeleteTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.GetTaskPushNotificationConfigParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsResult;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("deprecation")
class A2aServletTest {

    @Test
    void doPost_returns_bad_request_when_body_exceeds_limit() throws Exception {
        A2aServlet servlet = new A2aServlet(new RecordingRequestHandler());
        byte[] body = new byte[(1024 * 1024) + 1];
        for (int i = 0; i < body.length; i++) {
            body[i] = 'x';
        }
        TestHttpServletResponse response = new TestHttpServletResponse();

        servlet.doPost(request(body, null), response.asServletResponse());

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
        assertTrue(response.body().contains("Invalid request body"));
    }

    @Test
    void doPost_returns_bad_request_for_malformed_json() throws Exception {
        A2aServlet servlet = new A2aServlet(new RecordingRequestHandler());
        TestHttpServletResponse response = new TestHttpServletResponse();

        servlet.doPost(request("{".getBytes(StandardCharsets.UTF_8), null), response.asServletResponse());

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
        assertTrue(response.body().contains("Invalid JSON-RPC request"));
    }

    @Test
    void doPost_returns_bad_request_when_method_is_missing() throws Exception {
        A2aServlet servlet = new A2aServlet(new RecordingRequestHandler());
        TestHttpServletResponse response = new TestHttpServletResponse();

        servlet.doPost(request(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{}}".getBytes(StandardCharsets.UTF_8),
                null),
                response.asServletResponse());

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
        assertTrue(response.body().contains("JSON-RPC method is required"));
    }

    @Test
    void doPost_returns_bad_request_for_unknown_method() throws Exception {
        A2aServlet servlet = new A2aServlet(new RecordingRequestHandler());
        TestHttpServletResponse response = new TestHttpServletResponse();

        servlet.doPost(request(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"unknown\",\"params\":{}}"
                        .getBytes(StandardCharsets.UTF_8),
                null),
                response.asServletResponse());

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
        assertTrue(response.body().contains("Unsupported JSON-RPC method: unknown"));
    }

    @Test
    void doPost_writes_sync_response_for_send_message_without_sse_accept() throws Exception {
        RecordingRequestHandler handler = new RecordingRequestHandler();
        A2aServlet servlet = new A2aServlet(handler);
        MessageSendParams params = new MessageSendParams(
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .messageId("msg-1")
                        .parts(new TextPart("echo"))
                        .build(),
                null,
                null);
        String body = JsonUtil.toJson(new SendMessageRequest("req-1", params));
        TestHttpServletResponse response = new TestHttpServletResponse();

        servlet.doPost(request(body.getBytes(StandardCharsets.UTF_8), null), response.asServletResponse());

        assertEquals(HttpServletResponse.SC_OK, response.status());
        assertEquals("application/json", response.contentType());
        assertEquals(1, handler.messageSendCalls());
        assertEquals(0, handler.messageSendStreamCalls());
        assertTrue(response.body().contains("req-1"));
        assertTrue(response.body().contains("ok"));
    }

    @Test
    void doPost_streams_async_submission_publisher_events() throws Exception {
        AsyncStreamingRequestHandler handler = new AsyncStreamingRequestHandler();
        A2aServlet servlet = new A2aServlet(handler);
        MessageSendParams params = new MessageSendParams(
                Message.builder()
                        .role(Message.Role.ROLE_USER)
                        .messageId("msg-1")
                        .parts(new TextPart("echo"))
                        .build(),
                null,
                null);
        String body = JsonUtil.toJson(new SendMessageRequest("req-1", params));
        TestHttpServletRequest request = asyncRequest(body.getBytes(StandardCharsets.UTF_8), "text/event-stream");
        TestHttpServletResponse response = new TestHttpServletResponse();

        servlet.doPost(request.asServletRequest(), response.asServletResponse());

        assertTrue(request.awaitAsyncCompletion(5, TimeUnit.SECONDS));
        assertEquals(HttpServletResponse.SC_OK, response.status());
        assertEquals("text/event-stream", response.contentType());
        assertEquals(1, handler.messageSendStreamCalls());
        assertTrue(response.body().contains("first"));
        assertTrue(response.body().contains("second"));
    }

    private static HttpServletRequest request(byte[] body, String accept) {
        return new TestHttpServletRequest(body, accept, false).asServletRequest();
    }

    private static TestHttpServletRequest asyncRequest(byte[] body, String accept) {
        return new TestHttpServletRequest(body, accept, true);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        private ByteArrayServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }
    }

    private static final class TestHttpServletRequest {
        private final byte[] body;
        private final String accept;
        private final boolean asyncSupported;
        private final HttpServletRequest servletRequest;
        private volatile TestAsyncContext asyncContext;

        private TestHttpServletRequest(byte[] body, String accept, boolean asyncSupported) {
            this.body = body;
            this.accept = accept;
            this.asyncSupported = asyncSupported;
            this.servletRequest = (HttpServletRequest) Proxy.newProxyInstance(
                    HttpServletRequest.class.getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getInputStream" -> new ByteArrayServletInputStream(this.body);
                        case "getHeader" -> "Accept".equals(args[0]) ? this.accept : null;
                        case "isAsyncSupported" -> this.asyncSupported;
                        case "isAsyncStarted" -> asyncContext != null && !asyncContext.isCompleted();
                        case "startAsync" -> startAsync(args);
                        case "getAsyncContext" -> asyncContext;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private HttpServletRequest asServletRequest() {
            return servletRequest;
        }

        private boolean awaitAsyncCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return asyncContext != null && asyncContext.awaitCompletion(timeout, unit);
        }

        private AsyncContext startAsync(Object[] args) {
            if (!asyncSupported) {
                throw new IllegalStateException("async not supported");
            }
            ServletRequest request = args != null && args.length > 0
                    ? (ServletRequest) args[0]
                    : servletRequest;
            ServletResponse response = args != null && args.length > 1
                    ? (ServletResponse) args[1]
                    : null;
            asyncContext = new TestAsyncContext(request, response);
            return asyncContext;
        }
    }

    private static final class TestAsyncContext implements AsyncContext {
        private final ServletRequest request;
        private final ServletResponse response;
        private final CountDownLatch completed = new CountDownLatch(1);
        private final java.util.List<AsyncListener> listeners = new CopyOnWriteArrayList<>();
        private volatile long timeout;
        private volatile boolean done;

        private TestAsyncContext(ServletRequest request, ServletResponse response) {
            this.request = request;
            this.response = response;
        }

        @Override
        public ServletRequest getRequest() {
            return request;
        }

        @Override
        public ServletResponse getResponse() {
            return response;
        }

        @Override
        public boolean hasOriginalRequestAndResponse() {
            return true;
        }

        @Override
        public void dispatch() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dispatch(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dispatch(ServletContext context, String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete() {
            done = true;
            AsyncEvent event = new AsyncEvent(this, request, response);
            for (AsyncListener listener : listeners) {
                try {
                    listener.onComplete(event);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            completed.countDown();
        }

        @Override
        public void start(Runnable run) {
            new Thread(run).start();
        }

        @Override
        public void addListener(AsyncListener listener) {
            listeners.add(listener);
        }

        @Override
        public void addListener(AsyncListener listener, ServletRequest request, ServletResponse response) {
            listeners.add(listener);
        }

        @Override
        public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new ServletException(e);
            }
        }

        @Override
        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }

        @Override
        public long getTimeout() {
            return timeout;
        }

        private boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return completed.await(timeout, unit);
        }

        private boolean isCompleted() {
            return done;
        }
    }

    private static final class TestHttpServletResponse {
        private final StringWriter buffer = new StringWriter();
        private final PrintWriter writer = new PrintWriter(buffer) {
            @Override
            public void flush() {
                super.flush();
                committed = true;
            }
        };
        private final Map<String, String> headers = new LinkedHashMap<>();
        private int status;
        private String characterEncoding;
        private String contentType;
        private boolean committed;

        private HttpServletResponse asServletResponse() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setStatus" -> {
                            status = (int) args[0];
                            yield null;
                        }
                        case "setCharacterEncoding" -> {
                            characterEncoding = (String) args[0];
                            yield null;
                        }
                        case "setContentType" -> {
                            contentType = (String) args[0];
                            yield null;
                        }
                        case "setHeader" -> {
                            headers.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "getWriter" -> writer;
                        case "flushBuffer" -> {
                            writer.flush();
                            committed = true;
                            yield null;
                        }
                        case "isCommitted" -> committed;
                        case "getCharacterEncoding" -> characterEncoding;
                        case "getContentType" -> contentType;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private int status() {
            return status;
        }

        private String body() {
            writer.flush();
            return buffer.toString();
        }

        private String contentType() {
            return contentType;
        }
    }

    private static class RecordingRequestHandler implements RequestHandler {
        private int messageSendCalls;
        private int messageSendStreamCalls;

        @Override
        public Task onGetTask(TaskQueryParams params, ServerCallContext callContext) {
            throw unused();
        }

        @Override
        public ListTasksResult onListTasks(ListTasksParams params, ServerCallContext callContext) {
            throw unused();
        }

        @Override
        public Task onCancelTask(CancelTaskParams params, ServerCallContext callContext) {
            throw unused();
        }

        @Override
        public EventKind onMessageSend(MessageSendParams params, ServerCallContext callContext) {
            messageSendCalls++;
            return Message.builder()
                    .role(Message.Role.ROLE_AGENT)
                    .messageId("reply-1")
                    .parts(new TextPart("ok"))
                    .build();
        }

        @Override
        public Flow.Publisher<StreamingEventKind> onMessageSendStream(
                MessageSendParams params, ServerCallContext callContext) {
            messageSendStreamCalls++;
            return streamingPublisher();
        }

        @Override
        public TaskPushNotificationConfig onCreateTaskPushNotificationConfig(
                TaskPushNotificationConfig taskPushNotificationConfig, ServerCallContext callContext) {
            throw unused();
        }

        @Override
        public TaskPushNotificationConfig onGetTaskPushNotificationConfig(
                GetTaskPushNotificationConfigParams params, ServerCallContext callContext) {
            throw unused();
        }

        @Override
        public Flow.Publisher<StreamingEventKind> onSubscribeToTask(
                TaskIdParams params, ServerCallContext callContext) {
            throw unused();
        }

        @Override
        public ListTaskPushNotificationConfigsResult onListTaskPushNotificationConfigs(
                ListTaskPushNotificationConfigsParams params, ServerCallContext callContext) {
            throw unused();
        }

        @Override
        public void onDeleteTaskPushNotificationConfig(
                DeleteTaskPushNotificationConfigParams params, ServerCallContext callContext) {
            throw unused();
        }

        @Override
        public void validateRequestedTask(String taskId) throws A2AError {
        }

        int messageSendCalls() {
            return messageSendCalls;
        }

        int messageSendStreamCalls() {
            return messageSendStreamCalls;
        }

        protected Flow.Publisher<StreamingEventKind> streamingPublisher() {
            return subscriber -> {
                throw new AssertionError("Streaming should not be used");
            };
        }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException();
        }
    }

    private static final class AsyncStreamingRequestHandler extends RecordingRequestHandler {
        @Override
        protected Flow.Publisher<StreamingEventKind> streamingPublisher() {
            SubmissionPublisher<StreamingEventKind> publisher = new SubmissionPublisher<>();
            Thread.ofPlatform().start(() -> {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    publisher.closeExceptionally(e);
                    return;
                }
                publisher.submit(Message.builder()
                        .role(Message.Role.ROLE_AGENT)
                        .messageId("stream-1")
                        .parts(new TextPart("first"))
                        .build());
                publisher.submit(Message.builder()
                        .role(Message.Role.ROLE_AGENT)
                        .messageId("stream-2")
                        .parts(new TextPart("second"))
                        .build());
                publisher.close();
            });
            return publisher;
        }
    }
}
