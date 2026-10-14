package org.adcontextprotocol.adcp.http;

import org.adcontextprotocol.adcp.error.ResponseTooLargeError;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSRF-safe HTTP client for AdCP outbound requests.
 *
 * <p>Implements the four mitigations from {@code specs/ssrf-baseline.md}:
 * <ol>
 *   <li>Resolve DNS once, validate the full address set</li>
 *   <li>Validate resolved addresses against the SSRF policy</li>
 *   <li>{@code redirect: manual} (no transparent redirect-follow)</li>
 *   <li>Body cap — two of them, for two different jobs (see below)</li>
 * </ol>
 *
 * <h2>Two caps</h2>
 * <ul>
 *   <li><b>Probe cap</b> ({@link #maxResponseBytes()}, default 4 KiB) applies to
 *       {@link #send}, {@link #get} and {@link #post}. These are discovery
 *       probes — transport detection and {@code WWW-Authenticate} challenges —
 *       where only the status line and headers are used. An over-cap body stops
 *       being read and the response is flagged via
 *       {@link AdcpHttpResponse#truncated()}.</li>
 *   <li><b>Call cap</b> ({@link #maxCallResponseBytes()}, default 4 MiB) applies
 *       to everything whose body is parsed: {@link #getForAgentCard} and the MCP
 *       and A2A call transports built from {@link #newHttpClientBuilder()} /
 *       {@link #newMcpClientBuilder()}. Over-cap responses throw
 *       {@link org.adcontextprotocol.adcp.error.ResponseTooLargeError}; nothing
 *       is truncated.</li>
 * </ul>
 *
 * <p><b>Outbound coverage.</b> Discovery and agent-card requests are sent by
 * this client directly. The MCP
 * ({@code HttpClientStreamableHttpTransport} / {@code HttpClientSseClientTransport})
 * and A2A ({@code JdkA2AHttpClient}) call transports own their own sockets, so
 * they do not route through {@link #send}; they are instead built on a
 * {@link java.net.http.HttpClient} handed to them by
 * {@link #newHttpClientBuilder()}, which carries this client's connect timeout,
 * {@code followRedirects(NEVER)} policy and call-response cap. SSRF address
 * validation for those paths happens in
 * {@link org.adcontextprotocol.adcp.transport.ProtocolClient} before the call,
 * not inside the transport.
 *
 * <p>Built on {@link java.net.http.HttpClient} (JDK 21).
 *
 * <p>The client keeps the original URI authority unchanged so HTTPS uses the
 * intended hostname for TLS SNI and hostname verification. DNS validation
 * therefore happens at resolve time only and accepts the remaining TOCTOU
 * window before connect.
 *
 * @see SsrfPolicy
 * @see DnsPinResolver
 */
public final class AdcpHttpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AdcpHttpClient.class);

    /** Default body cap for discovery probes (4 KiB). */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024;

    /**
     * Default body cap for agent responses on the MCP and A2A call paths,
     * and for agent-card fetches: 4 MiB.
     *
     * <p>Agent responses are untrusted input. This cap is on by default so a
     * single peer cannot memory-DoS the caller with an oversized payload.
     * Raise it per client, per agent or per call — see
     * {@code specs/response-size-limits.md}.
     */
    public static final long DEFAULT_MAX_CALL_RESPONSE_BYTES = 4L * 1024 * 1024;

    /** Upper bound accepted by {@link Builder#maxResponseBytes} (64 MiB). */
    public static final long MAX_PROBE_RESPONSE_BYTES = 64L * 1024 * 1024;

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEFAULT_USER_AGENT = "adcp-java-sdk/0.1";

    private final SsrfPolicy ssrfPolicy;
    private final long maxResponseBytes;
    private final long maxCallResponseBytes;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final String userAgent;
    private final boolean requireHttps;
    private final HttpClient httpClient;

    private AdcpHttpClient(Builder builder) {
        this.ssrfPolicy = builder.ssrfPolicy;
        this.maxResponseBytes = builder.maxResponseBytes;
        this.maxCallResponseBytes = builder.maxCallResponseBytes;
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.userAgent = builder.userAgent;
        this.requireHttps = builder.requireHttps;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Creates a new builder with strict SSRF policy defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Sends an HTTP request with SSRF protection.
     *
     * <p>The hostname is resolved via DNS and all addresses are validated
     * against the {@link SsrfPolicy}. The original URI is preserved so TLS
     * SNI and hostname verification continue to use the hostname instead of
     * an IP literal. Redirects are never followed automatically.
     *
     * <p>This is the <em>probe</em> path: the body is capped at the probe cap
     * {@link #maxResponseBytes()} (4 KiB by default) and a longer body stops
     * being read, with {@link AdcpHttpResponse#truncated()} set. Callers that
     * parse the body must reject truncated responses — or use
     * {@link #getForAgentCard}, which enforces the strict call cap instead.
     *
     * @param method  HTTP method (GET, POST, etc.)
     * @param uri     target URI
     * @param headers additional headers to include
     * @param body    request body, or {@code null} for bodyless requests
     * @return the response with possible body truncation
     * @throws SsrfBlockedException if the target address is blocked
     * @throws IOException          on transport errors
     * @throws InterruptedException if the calling thread is interrupted
     */
    public AdcpHttpResponse send(
            String method,
            URI uri,
            Map<String, String> headers,
            @Nullable byte[] body) throws IOException, InterruptedException {

        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(method, "method");

        // Step 0: Enforce HTTPS when requireHttps is enabled.
        // Localhost/loopback is exempt for local development.
        if (requireHttps && "http".equalsIgnoreCase(uri.getScheme())) {
            String host = uri.getHost();
            if (host != null && !isLoopback(host)) {
                throw new IOException(
                        "Plain HTTP is not allowed when requireHttps is enabled: " + uri
                                + ". Use HTTPS or set requireHttps(false) for local development.");
            }
        }

        // Step 1: DNS resolve + SSRF validate. Keep the original URI so
        // HTTPS continues to use the hostname for TLS and hostname checks,
        // accepting the remaining resolve-to-connect TOCTOU window.
        URI validatedUri = validateUri(uri);

        // Step 2: Build the request with the validated URI
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(validatedUri)
                .timeout(readTimeout)
                .header("User-Agent", userAgent);

        // Add caller-supplied headers, skipping protected headers
        headers.forEach((name, value) -> {
            if (!isProtectedHeader(name)) {
                requestBuilder.header(name, value);
            } else {
                log.debug("Skipping protected header from caller: {}", name);
            }
        });

        // Set method + body
        if (body != null) {
            requestBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        // Always ask for an uncompressed body so the cap counts the bytes we
        // actually buffer. The JDK HttpClient never decompresses transparently.
        requestBuilder.setHeader("Accept-Encoding", "identity");

        // Step 3: Send with body-capped response handler
        HttpResponse<InputStream> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

        // Step 4: Read body with cap enforcement.
        // Ensure the InputStream is closed even if readBodyWithCap throws.
        try {
            return readBodyWithCap(response, maxResponseBytes, false, validatedUri);
        } catch (Throwable t) {
            try {
                response.body().close();
            } catch (Exception suppressed) {
                t.addSuppressed(suppressed);
            }
            throw t;
        }
    }

    /**
     * Convenience: GET request with no body.
     */
    public AdcpHttpResponse get(URI uri, Map<String, String> headers)
            throws IOException, InterruptedException {
        return send("GET", uri, headers, null);
    }

    /**
     * Sends a GET request to fetch an agent card, forwarding all caller-supplied headers
     * including {@code Authorization}.
     *
     * <p>Unlike {@link #get}, this method does not strip protected headers so that
     * private agent-card endpoints can be reached with the same credentials used for
     * the subsequent A2A RPC call. The safeguards that would normally make header
     * forwarding risky (transparent redirect-follow, DNS rebinding) are neutralised
     * by the client's {@code followRedirects(NEVER)} policy and SSRF validation.
     *
     * <p>The body is parsed by the caller, so it is held to the strict call cap
     * ({@link #maxCallResponseBytes()}): an over-cap card throws
     * {@link org.adcontextprotocol.adcp.error.ResponseTooLargeError} rather than
     * arriving truncated.
     *
     * @param uri     target URI (SSRF-validated)
     * @param headers headers to forward, including any auth material
     * @return the response, body capped at {@link #maxCallResponseBytes()}
     * @throws org.adcontextprotocol.adcp.error.ResponseTooLargeError if the card
     *         exceeds {@link #maxCallResponseBytes()}
     */
    public AdcpHttpResponse getForAgentCard(URI uri, Map<String, String> headers)
            throws IOException, InterruptedException {
        return getForAgentCard(uri, headers, maxCallResponseBytes);
    }

    /**
     * Fetches an agent card with an explicit response cap, so a per-agent or
     * per-call override applies to discovery as well as to the call itself.
     *
     * @param uri              target URI (SSRF-validated)
     * @param headers          headers to forward, including any auth material
     * @param maxResponseBytes response cap in bytes; must be positive
     * @throws org.adcontextprotocol.adcp.error.ResponseTooLargeError if the card
     *         exceeds {@code maxResponseBytes}
     */
    public AdcpHttpResponse getForAgentCard(URI uri, Map<String, String> headers,
                                            long maxResponseBytes)
            throws IOException, InterruptedException {
        Objects.requireNonNull(uri, "uri");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be positive: " + maxResponseBytes);
        }

        if (requireHttps && "http".equalsIgnoreCase(uri.getScheme())) {
            String host = uri.getHost();
            if (host != null && !isLoopback(host)) {
                throw new IOException(
                        "Plain HTTP is not allowed when requireHttps is enabled: " + uri
                                + ". Use HTTPS or set requireHttps(false) for local development.");
            }
        }

        URI validatedUri = validateUri(uri);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(validatedUri)
                .timeout(readTimeout)
                .header("User-Agent", userAgent);

        // Forward all headers including auth — redirects are already blocked (NEVER)
        // and the target was SSRF-validated above, so header leakage via redirect
        // or cross-origin requests is not possible.
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && value != null) {
                    requestBuilder.header(name, value);
                }
            });
        }

        requestBuilder.setHeader("Accept-Encoding", "identity");
        requestBuilder.method("GET", HttpRequest.BodyPublishers.noBody());

        HttpResponse<InputStream> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

        try {
            return readBodyWithCap(response, maxResponseBytes, true, validatedUri);
        } catch (Throwable t) {
            try {
                response.body().close();
            } catch (Exception suppressed) {
                t.addSuppressed(suppressed);
            }
            throw t;
        }
    }

    /**
     * Convenience: POST request with a body.
     */
    public AdcpHttpResponse post(URI uri, Map<String, String> headers, byte[] body)
            throws IOException, InterruptedException {
        return send("POST", uri, headers, body);
    }

    /** The SSRF policy in use. */
    public SsrfPolicy ssrfPolicy() {
        return ssrfPolicy;
    }

    /** Maximum probe response body size in bytes (see {@link #send}). */
    public long maxResponseBytes() {
        return maxResponseBytes;
    }

    /**
     * Maximum agent-response body size in bytes for paths whose body is parsed:
     * agent-card fetches and the MCP/A2A call transports. Default 4 MiB.
     */
    public long maxCallResponseBytes() {
        return maxCallResponseBytes;
    }

    /**
     * Creates an MCP transport client builder with the same connection-timeout,
     * redirect policy and response cap used by this client.
     */
    public HttpClient.Builder newMcpClientBuilder() {
        return newHttpClientBuilder();
    }

    /**
     * Creates an MCP transport client builder with an explicit response cap.
     *
     * @param maxResponseBytes per-connection response cap in bytes
     */
    public HttpClient.Builder newMcpClientBuilder(long maxResponseBytes) {
        return newHttpClientBuilder(maxResponseBytes);
    }

    /**
     * Creates an HTTP client builder with this client's connection-timeout,
     * redirect policy ({@code NEVER}) and call-response cap
     * ({@link #maxCallResponseBytes()}). Suitable for any transport
     * (MCP, A2A, etc.).
     */
    public HttpClient.Builder newHttpClientBuilder() {
        return newHttpClientBuilder(maxCallResponseBytes);
    }

    /**
     * Creates an HTTP client builder with an explicit response cap, for
     * transports that need a per-agent or per-call override.
     *
     * <p>The returned builder produces a
     * {@link ResponseSizeLimitingHttpClient}: every response it reads is
     * counted as it streams and aborted with
     * {@link org.adcontextprotocol.adcp.error.ResponseTooLargeError} once the
     * cap is crossed. One shape is treated specially — a {@code GET} answered
     * with {@code text/event-stream} is a long-lived listening stream and gets
     * a larger cumulative budget with the same per-event cap. See
     * {@link ResponseSizeLimitingHttpClient} for why, and for what that means
     * if you hand this builder to a transport of your own.
     *
     * @param maxResponseBytes response cap in bytes; must be positive
     */
    public HttpClient.Builder newHttpClientBuilder(long maxResponseBytes) {
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be positive: " + maxResponseBytes);
        }
        return new ResponseSizeLimitingHttpClient.LimitingClientBuilder(
                HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER),
                maxResponseBytes);
    }

    @Override
    public void close() {
        httpClient.close();
    }

    // -- internal --

    private URI validateUri(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("URI scheme must be http or https: " + uri);
        }

        String host = uri.getHost();
        if (host == null) {
            throw new IOException("URI has no host: " + uri);
        }

        // Syntactic check for IP literals
        if (isIpLiteral(host)) {
            InetAddress literal = InetAddress.getByName(host);
            DnsPinResolver.validateAddress(literal, ssrfPolicy);
            return uri;
        }

        // Resolve hostname and validate every address, but keep the original
        // hostname in the URI so TLS SNI and hostname verification work.
        // This accepts the remaining TOCTOU window between validation and connect.
        DnsPinResolver.resolveAndPin(host, ssrfPolicy);
        return uri;
    }

    private static boolean isIpLiteral(String host) {
        // IPv6 in URI brackets: [::1]
        if (host.startsWith("[")) {
            return true;
        }
        // Must have at least one dot for IPv4
        if (host.indexOf('.') < 0) {
            return false;
        }
        // Check: all characters are digits and dots
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        // Reject ambiguous octal/decimal literals (e.g. 0177.0.0.1).
        // InetAddress.getAllByName may interpret leading-zero octets as
        // octal on some JDKs, allowing SSRF bypass.
        for (String octet : host.split("\\.", -1)) {
            if (octet.length() > 1 && octet.startsWith("0")) {
                throw new org.adcontextprotocol.adcp.http.SsrfBlockedException(
                        host, "Ambiguous IP literal with leading zeros (possible octal)");
            }
        }
        return true;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host);
    }

    /**
     * Reads the body, counting bytes as they arrive and stopping at {@code cap}.
     *
     * <p>A declared {@code Content-Length} over the cap is rejected before any
     * body byte is read. In {@code strict} mode an over-cap body throws
     * {@link ResponseTooLargeError}; otherwise (probe paths, where the body is
     * not parsed) reading stops at the cap and the result is flagged via
     * {@link AdcpHttpResponse#truncated()}.
     *
     * <p>The read is also bounded in <em>time</em>. {@code HttpRequest.timeout}
     * only covers the status line and headers, so without this a peer that
     * sends headers and then drips — or stalls outright — would pin the calling
     * thread and its socket for as long as it liked. A watchdog closes the
     * stream at {@code readTimeout}, which unblocks a stalled {@code read()},
     * and the loop checks the same deadline so a drip-feed gets a clear error.
     */
    private AdcpHttpResponse readBodyWithCap(HttpResponse<InputStream> response,
                                             long cap, boolean strict, URI uri)
            throws IOException {
        if (strict) {
            long declared = declaredContentLength(response);
            if (declared > cap) {
                throw new ResponseTooLargeError(cap, declared, uri);
            }
        }

        boolean truncated = false;
        long totalRead = 0;
        long deadline = System.nanoTime() + readTimeout.toNanos();

        try (InputStream is = response.body()) {
            // Runs on the JDK's shared delayed-task thread; cancelling before it
            // fires stops it from running at all.
            CompletableFuture<Void> watchdog = CompletableFuture.runAsync(
                    () -> closeQuietly(is),
                    CompletableFuture.delayedExecutor(
                            readTimeout.toMillis(), TimeUnit.MILLISECONDS));
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(
                        (int) Math.min(cap, 8192));
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    if (System.nanoTime() - deadline > 0) {
                        throw new HttpTimeoutException(
                                "Response body from " + redact(uri) + " not delivered within "
                                        + readTimeout + " (read " + totalRead + " bytes)");
                    }
                    totalRead += n;
                    if (totalRead <= cap) {
                        baos.write(buf, 0, n);
                        continue;
                    }
                    if (strict) {
                        throw new ResponseTooLargeError(cap, totalRead, uri);
                    }
                    // Probe path: keep the bytes up to the cap, flag, stop reading.
                    int remaining = (int) (cap - (totalRead - n));
                    if (remaining > 0) {
                        baos.write(buf, 0, remaining);
                    }
                    truncated = true;
                    log.debug("Probe response body truncated at {} bytes (cap={})",
                            totalRead, cap);
                    break;
                }

                return new AdcpHttpResponse(
                        response.statusCode(),
                        response.headers(),
                        baos.toByteArray(),
                        truncated,
                        totalRead);
            } finally {
                watchdog.cancel(false);
            }
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("Error closing response body on read timeout: {}", e.toString());
        }
    }

    /**
     * Strips userinfo and query from a URI before it lands in an exception
     * message or a log line — both routinely carry credentials.
     */
    static URI redact(URI uri) {
        if (uri.getRawUserInfo() == null && uri.getRawQuery() == null) {
            return uri;
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null);
        } catch (URISyntaxException e) {
            return URI.create(String.valueOf(uri.getScheme()) + "://" + uri.getHost());
        }
    }

    private static long declaredContentLength(HttpResponse<?> response) {
        try {
            return response.headers().firstValueAsLong("content-length").orElse(-1L);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static boolean isProtectedHeader(String name) {
        return ProtectedHeaders.isProtected(name);
    }

    // -- Builder --

    public static final class Builder {
        private SsrfPolicy ssrfPolicy = SsrfPolicy.strict();
        private long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
        private long maxCallResponseBytes = DEFAULT_MAX_CALL_RESPONSE_BYTES;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private String userAgent = DEFAULT_USER_AGENT;
        private boolean requireHttps = false;

        private Builder() {}

        /**
         * Sets the SSRF policy. Defaults to {@link SsrfPolicy#strict()}.
         * Use {@link SsrfPolicy#permissive()} only for local development
         * against {@code localhost}.
         */
        public Builder ssrfPolicy(SsrfPolicy ssrfPolicy) {
            this.ssrfPolicy = Objects.requireNonNull(ssrfPolicy);
            return this;
        }

        /**
         * Maximum <em>probe</em> response body size in bytes. Probe responses
         * exceeding this stop being read and are flagged via
         * {@link AdcpHttpResponse#truncated()}.
         * Default: {@value #DEFAULT_MAX_RESPONSE_BYTES} (4 KiB).
         * Maximum: 64 MiB.
         *
         * <p>This is <em>not</em> the cap on agent responses — that is
         * {@link #maxCallResponseBytes(long)}.
         */
        public Builder maxResponseBytes(long maxResponseBytes) {
            if (maxResponseBytes <= 0 || maxResponseBytes > MAX_PROBE_RESPONSE_BYTES) {
                throw new IllegalArgumentException(
                        "maxResponseBytes must be in (0, " + MAX_PROBE_RESPONSE_BYTES
                                + "]: " + maxResponseBytes);
            }
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        /**
         * Maximum agent-response body size in bytes for paths whose body is
         * parsed: agent-card fetches and the MCP/A2A call transports.
         * Over-cap responses throw
         * {@link org.adcontextprotocol.adcp.error.ResponseTooLargeError}.
         *
         * <p>Default: 4 MiB ({@link #DEFAULT_MAX_CALL_RESPONSE_BYTES}). Raise it
         * only for agents that legitimately publish very large catalogues —
         * agent responses are untrusted input and the cap is what keeps one
         * peer from exhausting the caller's memory.
         */
        public Builder maxCallResponseBytes(long maxCallResponseBytes) {
            if (maxCallResponseBytes <= 0) {
                throw new IllegalArgumentException(
                        "maxCallResponseBytes must be positive: " + maxCallResponseBytes);
            }
            this.maxCallResponseBytes = maxCallResponseBytes;
            return this;
        }

        /** Connection timeout. Default: 10 seconds. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout);
            return this;
        }

        /** Read timeout per request. Default: 30 seconds. */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = Objects.requireNonNull(readTimeout);
            return this;
        }

        /** User-Agent header value. */
        public Builder userAgent(String userAgent) {
            this.userAgent = Objects.requireNonNull(userAgent);
            return this;
        }

        /**
         * When {@code true}, rejects plain {@code http://} URIs for
         * non-loopback hosts. Prevents credential leakage over unencrypted
         * connections in production.
         *
         * <p>Localhost ({@code 127.0.0.1}, {@code ::1}, {@code localhost})
         * is always exempt for local development.
         *
         * <p>Default: {@code false} (warns only, via
         * {@link org.adcontextprotocol.adcp.AgentConfig}).
         */
        public Builder requireHttps(boolean requireHttps) {
            this.requireHttps = requireHttps;
            return this;
        }

        /** Builds the client. */
        public AdcpHttpClient build() {
            return new AdcpHttpClient(this);
        }
    }
}
