package org.adcontextprotocol.adcp.http;

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
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * SSRF-safe HTTP client for AdCP outbound requests.
 *
 * <p>Implements the four mitigations from {@code specs/ssrf-baseline.md}:
 * <ol>
 *   <li>Resolve DNS once, validate the full address set</li>
 *   <li>Validate resolved addresses against the SSRF policy</li>
 *   <li>{@code redirect: manual} (no transparent redirect-follow)</li>
 *   <li>Body cap (default 4 KiB for probes, configurable per call)</li>
 * </ol>
 *
 * <p>Every outbound HTTP call in the SDK routes through this client.
 * Built on {@link java.net.http.HttpClient} (JDK 21).
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

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEFAULT_USER_AGENT = "adcp-java-sdk/0.1";

    private final SsrfPolicy ssrfPolicy;
    private final long maxResponseBytes;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final String userAgent;
    private final boolean requireHttps;
    private final HttpClient httpClient;

    private AdcpHttpClient(Builder builder) {
        this.ssrfPolicy = builder.ssrfPolicy;
        this.maxResponseBytes = builder.maxResponseBytes;
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
     * an IP literal. Redirects are never followed automatically. The response
     * body is capped at {@link #maxResponseBytes()}.
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

        // Step 3: Send with body-capped response handler
        HttpResponse<InputStream> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

        // Step 4: Read body with cap enforcement.
        // Ensure the InputStream is closed even if readBodyWithCap throws.
        try {
            return readBodyWithCap(response);
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
     * @param uri     target URI (SSRF-validated)
     * @param headers headers to forward, including any auth material
     * @return the response, body capped at {@link #maxResponseBytes()}
     */
    public AdcpHttpResponse getForAgentCard(URI uri, Map<String, String> headers)
            throws IOException, InterruptedException {
        Objects.requireNonNull(uri, "uri");

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

        requestBuilder.method("GET", HttpRequest.BodyPublishers.noBody());

        HttpResponse<InputStream> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

        try {
            return readBodyWithCap(response);
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

    /** Maximum response body size in bytes. */
    public long maxResponseBytes() {
        return maxResponseBytes;
    }

    /**
     * Creates an MCP transport client builder with the same connection-timeout
     * and redirect policy used by this client.
     */
    public HttpClient.Builder newMcpClientBuilder() {
        return newHttpClientBuilder();
    }

    /**
     * Creates an HTTP client builder with this client's connection-timeout and
     * redirect policy ({@code NEVER}). Suitable for any transport (MCP, A2A, etc.).
     */
    public HttpClient.Builder newHttpClientBuilder() {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER);
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

    private AdcpHttpResponse readBodyWithCap(HttpResponse<InputStream> response)
            throws IOException {
        long cap = maxResponseBytes;
        boolean truncated = false;
        long totalRead = 0;

        try (InputStream is = response.body()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(
                    (int) Math.min(cap, 8192));
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                totalRead += n;
                if (totalRead <= cap) {
                    baos.write(buf, 0, n);
                } else if (!truncated) {
                    // Write only the portion up to the cap
                    int remaining = (int) (cap - (totalRead - n));
                    if (remaining > 0) {
                        baos.write(buf, 0, remaining);
                    }
                    truncated = true;
                    log.debug("Response body truncated at {} bytes (cap={})",
                            totalRead, cap);
                    // Stop reading — don't consume the rest
                    break;
                }
            }

            return new AdcpHttpResponse(
                    response.statusCode(),
                    response.headers(),
                    baos.toByteArray(),
                    truncated,
                    totalRead);
        }
    }

    private static boolean isProtectedHeader(String name) {
        return ProtectedHeaders.isProtected(name);
    }

    // -- Builder --

    public static final class Builder {
        private SsrfPolicy ssrfPolicy = SsrfPolicy.strict();
        private long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
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
         * Maximum response body size in bytes. Responses exceeding this
         * are truncated and flagged via {@link AdcpHttpResponse#truncated()}.
         * Default: {@value #DEFAULT_MAX_RESPONSE_BYTES} (4 KiB).
         * Maximum: 64 MB.
         */
        public Builder maxResponseBytes(long maxResponseBytes) {
            if (maxResponseBytes <= 0 || maxResponseBytes > 64 * 1024 * 1024) {
                throw new IllegalArgumentException(
                        "maxResponseBytes must be in (0, 67108864]: " + maxResponseBytes);
            }
            this.maxResponseBytes = maxResponseBytes;
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
