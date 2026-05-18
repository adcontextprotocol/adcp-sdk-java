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
 *   <li>Pin the connect to the first validated address</li>
 *   <li>{@code redirect: manual} (no transparent redirect-follow)</li>
 *   <li>Body cap (default 4 KiB for probes, configurable per call)</li>
 * </ol>
 *
 * <p>Every outbound HTTP call in the SDK routes through this client.
 * Built on {@link java.net.http.HttpClient} (JDK 21).
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
    private final HttpClient httpClient;

    private AdcpHttpClient(Builder builder) {
        this.ssrfPolicy = builder.ssrfPolicy;
        this.maxResponseBytes = builder.maxResponseBytes;
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.userAgent = builder.userAgent;
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
     * <p>The hostname is resolved via DNS, all addresses are validated
     * against the {@link SsrfPolicy}, and the connection is pinned to the
     * first validated address. Redirects are never followed automatically.
     * The response body is capped at {@link #maxResponseBytes()}.
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

        // Step 1: DNS resolve + SSRF validate + pin
        URI pinnedUri = pinUri(uri);

        // Step 2: Build the request with the pinned URI
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(pinnedUri)
                .timeout(readTimeout)
                .header("User-Agent", userAgent);

        // Inject Host header for the original hostname (the URI now has the IP)
        String originalHost = uri.getHost();
        if (originalHost != null && !originalHost.equals(pinnedUri.getHost())) {
            String hostValue = uri.getPort() > 0 && uri.getPort() != defaultPort(uri.getScheme())
                    ? originalHost + ":" + uri.getPort()
                    : originalHost;
            requestBuilder.header("Host", hostValue);
        }

        // Add caller-supplied headers
        headers.forEach(requestBuilder::header);

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

        // Step 4: Read body with cap enforcement
        return readBodyWithCap(response);
    }

    /**
     * Convenience: GET request with no body.
     */
    public AdcpHttpResponse get(URI uri, Map<String, String> headers)
            throws IOException, InterruptedException {
        return send("GET", uri, headers, null);
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

    @Override
    public void close() {
        // HttpClient in JDK 21 doesn't require explicit close,
        // but we implement AutoCloseable for forward compatibility.
    }

    // -- internal --

    private URI pinUri(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("URI has no host: " + uri);
        }

        // Check if the host is already a literal IP address
        try {
            InetAddress literal = InetAddress.getByName(host);
            if (host.equals(literal.getHostAddress()) || host.startsWith("[")) {
                // Already a literal IP — just validate it
                DnsPinResolver.validateAddress(literal, ssrfPolicy);
                return uri;
            }
        } catch (Exception ignored) {
            // Not a literal IP — proceed with DNS resolution
        }

        // Resolve hostname and pin to first validated address
        InetAddress pinned = DnsPinResolver.resolveAndPin(host, ssrfPolicy);
        String pinnedHost = pinned.getHostAddress();

        // IPv6 addresses need brackets in URIs
        if (pinnedHost.contains(":")) {
            pinnedHost = "[" + pinnedHost + "]";
        }

        // Reconstruct URI with the pinned IP
        try {
            return new URI(
                    uri.getScheme(),
                    null, // userInfo
                    pinned.getHostAddress(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment());
        } catch (Exception e) {
            throw new IOException("Failed to construct pinned URI for " + host, e);
        }
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

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    // -- Builder --

    public static final class Builder {
        private SsrfPolicy ssrfPolicy = SsrfPolicy.strict();
        private long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;
        private String userAgent = DEFAULT_USER_AGENT;

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
         */
        public Builder maxResponseBytes(long maxResponseBytes) {
            if (maxResponseBytes <= 0) {
                throw new IllegalArgumentException(
                        "maxResponseBytes must be positive: " + maxResponseBytes);
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

        /** Builds the client. */
        public AdcpHttpClient build() {
            return new AdcpHttpClient(this);
        }
    }
}
