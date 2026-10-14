package org.adcontextprotocol.adcp.http;

import org.adcontextprotocol.adcp.error.ResponseTooLargeError;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * A {@link HttpClient} decorator that enforces a response-size cap
 * <em>while the body streams in</em>.
 *
 * <p>Agent responses are untrusted input. Without a cap, a single peer can
 * exhaust the caller's memory by answering a tool call with a multi-gigabyte
 * body. This decorator exists because the MCP
 * ({@code HttpClientStreamableHttpTransport} / {@code HttpClientSseClientTransport})
 * and A2A ({@code JdkA2AHttpClient}) transports accept a JDK {@link HttpClient}
 * but expose no body-size hook of their own — wrapping the client is the only
 * choke point that covers every call they make.
 *
 * <p>Enforcement:
 * <ol>
 *   <li><b>Pre-reject on {@code Content-Length}.</b> A declared length above
 *       the cap fails before a single body byte is read.</li>
 *   <li><b>Count while reading.</b> Bytes are counted in
 *       {@link Flow.Subscriber#onNext}; the subscription is cancelled the
 *       moment the cap is crossed, so the oversized body is never buffered.</li>
 *   <li><b>SSE per event and per call.</b> For
 *       {@code Content-Type: text/event-stream}, bytes are counted per event
 *       (reset at each blank-line delimiter, accepting CR, LF and CRLF framing)
 *       so one huge event cannot escape the cap, <em>and</em> cumulatively for
 *       the whole response. Answering a call with {@code text/event-stream}
 *       instead of {@code application/json} therefore buys no extra budget, and
 *       neither does drip-feeding small events.</li>
 *   <li><b>Decompressed bytes.</b> Outbound requests are rewritten to
 *       {@code Accept-Encoding: identity} so the counted wire bytes are the
 *       bytes the caller will hold. The JDK {@link HttpClient} never
 *       transparently decompresses, so a peer cannot hide a 1 GiB payload
 *       behind a 1 MiB gzip stream.</li>
 * </ol>
 *
 * <h2>Long-lived listening streams</h2>
 *
 * <p>One response shape gets a larger cumulative budget: a {@code GET} answered
 * with {@code text/event-stream}. That is the MCP SSE session channel, which
 * stays open for the life of the connection and carries every server-to-client
 * message, so holding its running total to a single call's cap would kill
 * healthy long sessions. Its per-event cap is unchanged — and per-event is the
 * cap that bounds memory there, because the transports aggregate one event at a
 * time and discard the bytes in between. Its cumulative budget is
 * {@code maxResponseBytes * }{@value #LISTENING_STREAM_MULTIPLIER}, finite so
 * that a peer cannot hold a {@code GET} open indefinitely simply by choosing an
 * SSE content type.
 *
 * <h2>Reporting</h2>
 *
 * <p>Oversized responses fail the response future with
 * {@link ResponseTooLargeError} — never truncated, never silently dropped.
 * Because the JDK may wrap a body-subscriber failure before it reaches the
 * caller, use {@link ResponseTooLargeError#findIn(Throwable)} to recover the
 * typed error from a cause chain.
 *
 * <p>With a <em>streaming</em> body handler ({@code BodyHandlers.ofInputStream},
 * {@code ofPublisher}) the response future completes normally at the headers and
 * the error surfaces on the first read past the cap instead. The SDK's own paths
 * use aggregating handlers; callers wrapping their own client should keep that
 * in mind.
 *
 * <p><b>Scope.</b> The cap is per in-flight response. It bounds what one peer
 * can make this process hold for one call — it is not a global memory budget,
 * so a caller fanning out across many agents still has to bound its own
 * concurrency.
 *
 * @see AdcpHttpClient#newHttpClientBuilder(long)
 * @see ResponseTooLargeError
 */
public final class ResponseSizeLimitingHttpClient extends HttpClient {

    private static final Logger log =
            LoggerFactory.getLogger(ResponseSizeLimitingHttpClient.class);

    /**
     * How much more cumulative traffic a long-lived {@code GET} event stream may
     * carry than a single call response, before it is torn down anyway.
     *
     * <p>The MCP SSE session channel stays open for the life of the connection,
     * so capping its running total at the per-call cap would kill healthy long
     * sessions. It still gets a finite ceiling: without one, a peer could hold
     * a {@code GET} open forever by answering with {@code text/event-stream},
     * and that choice would be the peer's to make, not ours.
     */
    public static final int LISTENING_STREAM_MULTIPLIER = 256;

    private static final String SSE_CONTENT_TYPE = "text/event-stream";
    private static final String ACCEPT_ENCODING = "Accept-Encoding";
    private static final String IDENTITY = "identity";

    private final HttpClient delegate;
    private final long maxResponseBytes;

    private ResponseSizeLimitingHttpClient(HttpClient delegate, long maxResponseBytes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be positive: " + maxResponseBytes);
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Wraps {@code delegate} so that every response it produces is capped at
     * {@code maxResponseBytes}.
     *
     * <p>The cap applies per response: to the whole body, to each SSE event,
     * and cumulatively across a response's events. A {@code GET} answered with
     * {@code text/event-stream} is treated as a long-lived listening stream and
     * gets {@value #LISTENING_STREAM_MULTIPLIER}× the cumulative budget, with
     * its per-event cap unchanged — see the class javadoc.
     *
     * @param delegate         the client to decorate
     * @param maxResponseBytes cap in bytes; must be positive
     * @return the decorated client
     */
    public static ResponseSizeLimitingHttpClient wrap(HttpClient delegate,
                                                      long maxResponseBytes) {
        return new ResponseSizeLimitingHttpClient(delegate, maxResponseBytes);
    }

    /** The cap in bytes applied to every response from this client. */
    public long maxResponseBytes() {
        return maxResponseBytes;
    }

    // -- HttpClient: the three methods that actually carry a body --

    @Override
    public <T> HttpResponse<T> send(HttpRequest request,
                                    HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        return delegate.send(identityEncoding(request),
                limiting(request, responseBodyHandler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        return delegate.sendAsync(identityEncoding(request),
                limiting(request, responseBodyHandler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.@Nullable PushPromiseHandler<T> pushPromiseHandler) {
        return delegate.sendAsync(identityEncoding(request),
                limiting(request, responseBodyHandler),
                limiting(pushPromiseHandler));
    }

    // -- HttpClient: pure delegation --

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    @Override
    public Version version() {
        return delegate.version();
    }

    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
        return delegate.newWebSocketBuilder();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public boolean awaitTermination(Duration duration) throws InterruptedException {
        return delegate.awaitTermination(duration);
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public void shutdownNow() {
        delegate.shutdownNow();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public String toString() {
        return "ResponseSizeLimitingHttpClient[maxResponseBytes=" + maxResponseBytes
                + ", delegate=" + delegate + "]";
    }

    // -- internal --

    /**
     * Rewrites the request to ask for an uncompressed body, so the bytes this
     * client counts are the bytes the caller ends up holding.
     */
    private static HttpRequest identityEncoding(HttpRequest request) {
        String current = request.headers().firstValue(ACCEPT_ENCODING).orElse(null);
        if (IDENTITY.equalsIgnoreCase(current)) {
            return request;
        }
        if (current != null) {
            log.debug("Replacing Accept-Encoding '{}' with 'identity' so the response"
                    + " size cap counts uncompressed bytes", current);
        }
        return HttpRequest.newBuilder(request,
                        (name, value) -> !ACCEPT_ENCODING.equalsIgnoreCase(name))
                .header(ACCEPT_ENCODING, IDENTITY)
                .build();
    }

    private <T> HttpResponse.BodyHandler<T> limiting(
            HttpRequest request, HttpResponse.BodyHandler<T> delegateHandler) {
        Objects.requireNonNull(delegateHandler, "responseBodyHandler");
        // A GET that comes back as SSE is a long-lived listening stream, not the
        // response to a single call, so its running total gets a larger ceiling.
        boolean listeningStream = "GET".equalsIgnoreCase(request.method());
        URI uri = request.uri();
        long cap = maxResponseBytes;
        return responseInfo -> {
            // A declared Content-Length is held to the per-call cap even on a
            // GET event stream: a body of known, finite size is a payload, not a
            // listening stream, so it gets no share of the larger ceiling.
            long declared = declaredContentLength(responseInfo);
            if (declared > cap) {
                log.debug("Rejecting response from {}: Content-Length {} exceeds cap {}",
                        redact(uri), declared, cap);
                return new FailedBodySubscriber<>(
                        new ResponseTooLargeError(cap, declared, uri));
            }
            boolean sse = isEventStream(responseInfo);
            long totalCap = sse && listeningStream
                    ? saturatingMultiply(cap, LISTENING_STREAM_MULTIPLIER)
                    : cap;
            return new LimitingBodySubscriber<>(
                    delegateHandler.apply(responseInfo), cap, totalCap, sse, uri);
        };
    }

    private <T> HttpResponse.@Nullable PushPromiseHandler<T> limiting(
            HttpResponse.@Nullable PushPromiseHandler<T> delegateHandler) {
        if (delegateHandler == null) {
            return null;
        }
        return (initiatingRequest, pushPromiseRequest, acceptor) ->
                delegateHandler.applyPushPromise(initiatingRequest, pushPromiseRequest,
                        handler -> acceptor.apply(limiting(pushPromiseRequest, handler)));
    }

    /** Keeps credentials in a URI out of log lines. */
    private static Object redact(@Nullable URI uri) {
        return uri == null ? "(unknown)" : AdcpHttpClient.redact(uri);
    }

    private static long saturatingMultiply(long value, int factor) {
        try {
            return Math.multiplyExact(value, (long) factor);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long declaredContentLength(HttpResponse.ResponseInfo responseInfo) {
        try {
            return responseInfo.headers().firstValueAsLong("content-length").orElse(-1L);
        } catch (NumberFormatException e) {
            // Malformed Content-Length — treat as unknown and let the streaming
            // counter do the work.
            return -1L;
        }
    }

    private static boolean isEventStream(HttpResponse.ResponseInfo responseInfo) {
        return responseInfo.headers().firstValue("content-type")
                .map(ct -> ct.toLowerCase(Locale.ROOT).contains(SSE_CONTENT_TYPE))
                .orElse(false);
    }

    /**
     * Fails the response immediately without reading any body — used when
     * {@code Content-Length} already declares an oversized payload.
     */
    private static final class FailedBodySubscriber<T>
            implements HttpResponse.BodySubscriber<T> {

        private final Throwable error;

        FailedBodySubscriber(Throwable error) {
            this.error = error;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            // never requested
        }

        @Override
        public void onError(Throwable throwable) {
            // already failed
        }

        @Override
        public void onComplete() {
            // already failed
        }

        @Override
        public CompletionStage<T> getBody() {
            return CompletableFuture.failedFuture(error);
        }
    }

    /**
     * Counts bytes as the body streams in and aborts the subscription the
     * moment the cap is crossed.
     *
     * <p>The reactive-streams contract guarantees {@code onNext} is signalled
     * serially, so the counters need no locking — but successive signals may
     * arrive on different carrier threads, so they are {@code volatile} for
     * visibility. The per-byte scan works on locals and writes back once per
     * {@code onNext} to keep that cheap. The terminal-state flag is atomic
     * because {@code onError} may race with a cap breach.
     */
    private static final class LimitingBodySubscriber<T>
            implements HttpResponse.BodySubscriber<T> {

        private final HttpResponse.BodySubscriber<T> delegate;
        private final long maxBytes;
        private final long maxTotalBytes;
        private final boolean sse;
        private final @Nullable URI uri;
        private final CompletableFuture<T> body = new CompletableFuture<>();
        private final AtomicBoolean aborted = new AtomicBoolean();

        private volatile Flow.@Nullable Subscription subscription;

        private volatile long totalBytes;
        private volatile long eventBytes;
        private volatile int terminators;
        private volatile boolean pendingCr;

        LimitingBodySubscriber(HttpResponse.BodySubscriber<T> delegate, long maxBytes,
                               long maxTotalBytes, boolean sse, @Nullable URI uri) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.maxBytes = maxBytes;
            this.maxTotalBytes = maxTotalBytes;
            this.sse = sse;
            this.uri = uri;
            delegate.getBody().whenComplete((value, failure) -> {
                if (failure != null) {
                    body.completeExceptionally(failure);
                } else {
                    body.complete(value);
                }
            });
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (aborted.get()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                totalBytes += buffer.remaining();
                if (totalBytes > maxTotalBytes) {
                    abort(maxTotalBytes, totalBytes);
                    return;
                }
                if (sse && scanForOversizedEvent(buffer)) {
                    abort(maxBytes, eventBytes);
                    return;
                }
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable throwable) {
            if (aborted.get()) {
                return;
            }
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (aborted.get()) {
                return;
            }
            delegate.onComplete();
        }

        @Override
        public CompletionStage<T> getBody() {
            return body;
        }

        /**
         * Counts bytes since the last SSE event delimiter without consuming the
         * buffer (absolute gets only — the delegate still sees the full body).
         *
         * @return {@code true} if the current event has crossed the cap
         */
        private boolean scanForOversizedEvent(ByteBuffer buffer) {
            long bytes = eventBytes;
            int runOfTerminators = terminators;
            boolean cr = pendingCr;
            boolean oversized = false;

            for (int i = buffer.position(); i < buffer.limit(); i++) {
                byte b = buffer.get(i);
                bytes++;

                // SSE accepts CR, LF and CRLF as line terminators. A pending CR
                // always terminated its line; whether the current byte joins it
                // (LF), starts another terminator (CR), or is content is a
                // separate question — hence two independent flags.
                boolean endedLine;
                boolean contentByte;
                if (cr) {
                    cr = b == '\r';
                    endedLine = true;                 // the pending CR ended a line
                    contentByte = b != '\n' && b != '\r';
                } else if (b == '\r') {
                    cr = true;
                    endedLine = false;                // wait for the next byte
                    contentByte = false;
                } else {
                    endedLine = b == '\n';
                    contentByte = !endedLine;
                }

                boolean eventEnded = false;
                if (endedLine && ++runOfTerminators >= 2) {
                    // Two terminators in a row is a blank line: end of event.
                    runOfTerminators = 0;
                    bytes = contentByte ? 1 : 0;      // the content byte opens the next event
                    eventEnded = true;
                }
                if (contentByte) {
                    runOfTerminators = 0;
                }

                if (!eventEnded && bytes > maxBytes) {
                    oversized = true;
                    break;
                }
            }

            eventBytes = bytes;
            terminators = runOfTerminators;
            pendingCr = cr;
            return oversized;
        }

        private void abort(long limit, long bytesRead) {
            if (!aborted.compareAndSet(false, true)) {
                return;
            }
            ResponseTooLargeError error = new ResponseTooLargeError(limit, bytesRead, uri);
            log.debug("Aborting response from {}: {}", redact(uri), error.getMessage());
            // Complete our own future first so the delegate's terminal signal
            // cannot overwrite the typed error.
            body.completeExceptionally(error);
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
            try {
                delegate.onError(error);
            } catch (RuntimeException e) {
                log.debug("Delegate body subscriber rejected the abort signal: {}",
                        e.toString());
            }
        }
    }

    /**
     * An {@link HttpClient.Builder} that produces size-limited clients.
     *
     * <p>Lets a size cap be handed to third-party transports that only accept a
     * {@code HttpClient.Builder} (the MCP transports do exactly this).
     */
    static final class LimitingClientBuilder implements HttpClient.Builder {

        private final HttpClient.Builder delegate;
        private final long maxResponseBytes;

        LimitingClientBuilder(HttpClient.Builder delegate, long maxResponseBytes) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.maxResponseBytes = maxResponseBytes;
        }

        @Override
        public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
            delegate.cookieHandler(cookieHandler);
            return this;
        }

        @Override
        public HttpClient.Builder connectTimeout(Duration duration) {
            delegate.connectTimeout(duration);
            return this;
        }

        @Override
        public HttpClient.Builder sslContext(SSLContext sslContext) {
            delegate.sslContext(sslContext);
            return this;
        }

        @Override
        public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
            delegate.sslParameters(sslParameters);
            return this;
        }

        @Override
        public HttpClient.Builder executor(Executor executor) {
            delegate.executor(executor);
            return this;
        }

        @Override
        public HttpClient.Builder followRedirects(Redirect policy) {
            delegate.followRedirects(policy);
            return this;
        }

        @Override
        public HttpClient.Builder version(Version version) {
            delegate.version(version);
            return this;
        }

        @Override
        public HttpClient.Builder priority(int priority) {
            delegate.priority(priority);
            return this;
        }

        @Override
        public HttpClient.Builder proxy(ProxySelector proxySelector) {
            delegate.proxy(proxySelector);
            return this;
        }

        @Override
        public HttpClient.Builder authenticator(Authenticator authenticator) {
            delegate.authenticator(authenticator);
            return this;
        }

        @Override
        public HttpClient.Builder localAddress(InetAddress localAddr) {
            delegate.localAddress(localAddr);
            return this;
        }

        @Override
        public HttpClient build() {
            return wrap(delegate.build(), maxResponseBytes);
        }
    }
}
