package org.adcontextprotocol.adcp.transport.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.adcontextprotocol.adcp.AgentConfig;
import org.adcontextprotocol.adcp.error.ProtocolError;
import org.adcontextprotocol.adcp.error.ResponseTooLargeError;
import org.adcontextprotocol.adcp.http.AdcpHttpClient;
import org.adcontextprotocol.adcp.http.AdcpHttpResponse;
import org.adcontextprotocol.adcp.http.ProtectedHeaders;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages cached A2A clients keyed by agent URL, credential cache hash, and
 * non-secret sanitized discovery headers.
 *
 * <p>Headers are included in the cache key because agent-card discovery is
 * header-sensitive. The separate cache hash isolates clients by credentials
 * without storing raw secrets in the cache key. The response size cap is in the
 * key too: it is baked into the transport's {@link java.net.http.HttpClient},
 * so a call that overrides the cap must not reuse a client built with another.
 */
public final class A2aConnectionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(A2aConnectionManager.class);
    static final int MAX_CACHE_SIZE = 20;
    private static final int STRIPE_COUNT = 32;
    private static final int MAX_HEADERS = 50;
    private static final String JSONRPC_TRANSPORT = "JSONRPC";

    private final LinkedHashMap<String, Client> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock cacheLock = new ReentrantLock();
    private final Semaphore[] connectStripes;
    private final AgentCardLoader agentCardLoader;
    private final ClientFactory clientFactory;
    private final long defaultMaxResponseBytes;
    private volatile boolean closed;

    public A2aConnectionManager(AdcpHttpClient adcpHttpClient, ObjectMapper objectMapper) {
        this(new HttpAgentCardLoader(adcpHttpClient, objectMapper),
                new DefaultClientFactory(adcpHttpClient),
                adcpHttpClient.maxCallResponseBytes());
    }

    A2aConnectionManager(AgentCardLoader agentCardLoader, ClientFactory clientFactory) {
        this(agentCardLoader, clientFactory, AdcpHttpClient.DEFAULT_MAX_CALL_RESPONSE_BYTES);
    }

    A2aConnectionManager(AgentCardLoader agentCardLoader, ClientFactory clientFactory,
                         long defaultMaxResponseBytes) {
        this.agentCardLoader = Objects.requireNonNull(agentCardLoader, "agentCardLoader");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.defaultMaxResponseBytes = defaultMaxResponseBytes;
        this.connectStripes = new Semaphore[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            connectStripes[i] = new Semaphore(1);
        }
    }

    public Client getOrConnect(AgentConfig agent, Map<String, String> headers, String cacheHash) {
        return getOrConnect(agent, headers, cacheHash, null);
    }

    /**
     * Gets or creates a cached A2A client with an explicit response size cap.
     *
     * @param maxResponseBytes per-client response cap, or {@code null} for the
     *                         client default
     */
    public Client getOrConnect(AgentConfig agent, Map<String, String> headers, String cacheHash,
                               @Nullable Long maxResponseBytes) {
        if (closed) {
            throw new IllegalStateException("A2aConnectionManager is closed");
        }
        Objects.requireNonNull(cacheHash, "cacheHash");
        Objects.requireNonNull(headers, "headers");
        long cap = effectiveMaxResponseBytes(maxResponseBytes);
        Map<String, String> sanitizedAll = sanitizeHeaders(headers);
        Map<String, String> sanitizedForKey = filterProtected(sanitizedAll);
        String cacheKey = buildCacheKey(agent.agentUri(), sanitizedForKey, cacheHash, cap);

        cacheLock.lock();
        try {
            Client existing = cache.get(cacheKey);
            if (existing != null) {
                return existing;
            }
        } finally {
            cacheLock.unlock();
        }

        Semaphore stripe = connectStripes[(cacheKey.hashCode() & 0x7FFFFFFF) % STRIPE_COUNT];
        try {
            stripe.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProtocolError("a2a", "Interrupted while connecting to " + agent.agentUri(), e);
        }

        try {
            cacheLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("A2aConnectionManager is closed");
                }
                Client existing = cache.get(cacheKey);
                if (existing != null) {
                    return existing;
                }
            } finally {
                cacheLock.unlock();
            }

            Client client = connect(agent, sanitizedAll, cap);

            cacheLock.lock();
            try {
                if (closed) {
                    closeQuietly(client);
                    throw new IllegalStateException("A2aConnectionManager is closed");
                }
                cache.put(cacheKey, client);
                evictOldest();
            } finally {
                cacheLock.unlock();
            }
            return client;
        } finally {
            stripe.release();
        }
    }

    public void evict(URI agentUri) {
        // buildCacheKey always produces "agentUri#cacheHash[?headers]", so the bare
        // agentUri.toString() can never equal a cache key — only startsWith is needed.
        evictMatching(key -> key.startsWith(agentUri + "#"));
    }

    public void evict(URI agentUri, String cacheHash) {
        Objects.requireNonNull(cacheHash, "cacheHash");
        String prefix = agentUri + "#" + cacheHash + "#";
        // Evicts all cache entries for the given agent URI and credential hash,
        // regardless of which non-secret discovery headers or response cap they
        // used. This is intentional: on a transport error, all variants for
        // those credentials are assumed stale.
        evictMatching(key -> key.startsWith(prefix));
    }

    /** The response cap applied when a call does not override it. */
    public long defaultMaxResponseBytes() {
        return defaultMaxResponseBytes;
    }

    private long effectiveMaxResponseBytes(@Nullable Long override) {
        if (override == null) {
            return defaultMaxResponseBytes;
        }
        if (override <= 0) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be positive: " + override);
        }
        return override;
    }

    @Override
    public void close() {
        cacheLock.lock();
        try {
            closed = true;
            cache.values().forEach(this::closeQuietly);
            cache.clear();
        } finally {
            cacheLock.unlock();
        }
        try {
            clientFactory.close();
        } catch (RuntimeException e) {
            log.debug("Error closing A2A client factory: {}", sanitizeLogText(e.getMessage()));
        }
    }

    private Client connect(AgentConfig agent, Map<String, String> sanitizedHeaders, long cap) {
        try {
            AgentCard card = agentCardLoader.load(agent, sanitizedHeaders, cap);
            return clientFactory.create(card, cap);
        } catch (ProtocolError e) {
            throw e;
        } catch (ResponseTooLargeError e) {
            // An oversized agent card is a hard failure, not a connectivity blip.
            throw e;
        } catch (A2AClientException e) {
            throw new ProtocolError("a2a", "Failed to create A2A client for " + agent.agentUri(), e);
        } catch (Exception e) {
            throw new ProtocolError("a2a", "Failed to connect to A2A agent " + agent.agentUri(), e);
        }
    }

    /**
     * Builds a stable cache key from the agent URI, credential cache hash, and
     * non-secret sanitized discovery headers. Headers are sorted by name and URL-encoded so
     * the key is independent of insertion order and immune to key-collision via
     * crafted {@code =} or {@code &} characters.
     */
    static String buildCacheKey(URI agentUri, Map<String, String> sanitizedHeaders, String cacheHash) {
        return buildCacheKey(agentUri, sanitizedHeaders, cacheHash,
                AdcpHttpClient.DEFAULT_MAX_CALL_RESPONSE_BYTES);
    }

    static String buildCacheKey(URI agentUri, Map<String, String> sanitizedHeaders, String cacheHash,
                                long maxResponseBytes) {
        StringBuilder sb = new StringBuilder(agentUri.toString())
                .append('#')
                .append(cacheHash)
                .append('#')
                .append(maxResponseBytes);
        if (sanitizedHeaders.isEmpty()) {
            return sb.toString();
        }
        sb.append('?');
        // Normalize header key case so semantically-identical headers with different casing
        // (e.g. X-Tenant vs x-tenant) always produce the same cache key. Pre-sort by the
        // original key (case-sensitive TreeMap) before lowercasing so that among
        // case-insensitive duplicates the alphabetically-last original key always wins,
        // making resolution deterministic regardless of the input map's iteration order.
        TreeMap<String, String> normalizedHeaders = new TreeMap<>();
        for (var entry : new TreeMap<>(sanitizedHeaders).entrySet()) {
            normalizedHeaders.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue());
        }
        boolean first = true;
        for (var entry : normalizedHeaders.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private void evictOldest() {
        while (cache.size() > MAX_CACHE_SIZE) {
            var it = cache.entrySet().iterator();
            if (it.hasNext()) {
                var entry = it.next();
                it.remove();
                closeQuietly(entry.getValue());
            }
        }
    }

    private static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (var entry : headers.entrySet()) {
            if (sanitized.size() >= MAX_HEADERS) {
                log.warn("Ignoring excess A2A discovery headers (>{}) to prevent cache-key bloat", MAX_HEADERS);
                break;
            }
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || value == null || hasCrlf(name) || hasCrlf(value)) {
                log.warn("Rejecting A2A discovery header (null or CR/LF): {}", sanitizeForLog(name));
                continue;
            }
            sanitized.put(name, value);
        }
        return sanitized;
    }

    private static Map<String, String> filterProtected(Map<String, String> headers) {
        Map<String, String> filtered = new LinkedHashMap<>();
        for (var entry : headers.entrySet()) {
            if (ProtectedHeaders.isProtected(entry.getKey())) {
                continue;
            }
            filtered.put(entry.getKey(), entry.getValue());
        }
        return filtered;
    }

    private void evictMatching(java.util.function.Predicate<String> matcher) {
        cacheLock.lock();
        try {
            List<String> toEvict = new ArrayList<>();
            for (String key : cache.keySet()) {
                if (matcher.test(key)) {
                    toEvict.add(key);
                }
            }
            for (String key : toEvict) {
                Client evicted = cache.remove(key);
                if (evicted != null) {
                    closeQuietly(evicted);
                }
            }
        } finally {
            cacheLock.unlock();
        }
    }

    private static boolean hasCrlf(String s) {
        return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
    }

    /** Strips all control characters and truncates for safe inclusion in log messages. */
    private static String sanitizeForLog(String s) {
        if (s == null) return "(null)";
        String t = s.length() > 128 ? s.substring(0, 128) + "..." : s;
        return t.replaceAll("[\\p{Cc}]", "");
    }

    private void closeQuietly(Client client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Error closing A2A client: {}", sanitizeLogText(e.getMessage()));
        } finally {
            try {
                // Client.close() only closes the A2A transport; the JDK HTTP
                // client underneath it belongs to the factory.
                clientFactory.release(client);
            } catch (RuntimeException e) {
                log.debug("Error releasing A2A transport resources: {}",
                        sanitizeLogText(e.getMessage()));
            }
        }
    }

    private static String sanitizeLogText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "(no detail)";
        }
        String truncated = raw.length() > 256 ? raw.substring(0, 256) + "..." : raw;
        return truncated.replaceAll("[\\p{Cc}]", "");
    }

    interface AgentCardLoader {
        AgentCard load(AgentConfig agent, Map<String, String> headers, long maxResponseBytes);
    }

    interface ClientFactory {
        Client create(AgentCard agentCard, long maxResponseBytes) throws A2AClientException;

        /**
         * Releases transport resources the factory allocated for {@code client}.
         * Called once per client, when the connection cache lets it go.
         */
        default void release(Client client) {
        }

        /** Releases anything the factory still owns after every client is gone. */
        default void close() {
        }
    }

    static final class DefaultClientFactory implements ClientFactory {

        private final AdcpHttpClient adcpHttpClient;

        /**
         * SSRF-safe HTTP clients used by the A2A JSON-RPC transport, one per
         * distinct response cap, reference-counted by the {@link Client}s using
         * them.
         *
         * <p>Each is backed by a {@link java.net.http.HttpClient} built with
         * {@code followRedirects(NEVER)}, the configured connect timeout, and a
         * streaming response-size cap, so the transport can neither follow HTTP
         * redirects to internal addresses nor be made to buffer an oversized
         * agent response. Combined with {@link HttpAgentCardLoader#normalize}
         * pinning AgentCard URLs to the validated agent URI, this closes the
         * SSRF bypass that would otherwise exist in the default
         * {@code JdkA2AHttpClient} (which uses {@code Redirect.NORMAL}).
         *
         * <p>A JDK HTTP client owns a selector thread and an executor, and
         * neither {@code JdkA2AHttpClient} nor {@code Client.close()} releases
         * it — so the factory tracks ownership itself. Clients built for the
         * same cap share one (the overwhelmingly common case: every agent on
         * the default), and the last one evicted takes it down. The reference
         * count, not a fixed pool bound, is what keeps a caller who varies the
         * cap per call from accumulating HTTP clients.
         */
        private final Map<Long, PooledHttpClient> pool = new HashMap<>();
        private final Map<Client, Long> capByClient = new IdentityHashMap<>();
        private final Object poolLock = new Object();

        DefaultClientFactory(AdcpHttpClient adcpHttpClient) {
            this.adcpHttpClient = Objects.requireNonNull(adcpHttpClient, "adcpHttpClient");
        }

        @Override
        public Client create(AgentCard agentCard, long maxResponseBytes)
                throws A2AClientException {
            ClientConfig config = ClientConfig.builder()
                    .setStreaming(true)
                    .setUseClientPreference(true)
                    .build();
            java.net.http.HttpClient httpClient = acquire(maxResponseBytes);
            boolean handedOff = false;
            try {
                Client client = Client.builder(agentCard)
                        .clientConfig(config)
                        .withTransport(JSONRPCTransport.class,
                                new JSONRPCTransportConfigBuilder()
                                        .httpClient(new JdkA2AHttpClient(httpClient)))
                        .build();
                synchronized (poolLock) {
                    capByClient.put(client, maxResponseBytes);
                }
                handedOff = true;
                return client;
            } finally {
                if (!handedOff) {
                    release(maxResponseBytes);
                }
            }
        }

        @Override
        public void release(Client client) {
            Long cap;
            synchronized (poolLock) {
                cap = capByClient.remove(client);
            }
            if (cap != null) {
                release(cap.longValue());
            }
        }

        @Override
        public void close() {
            List<java.net.http.HttpClient> orphaned;
            synchronized (poolLock) {
                orphaned = pool.values().stream().map(PooledHttpClient::httpClient).toList();
                pool.clear();
                capByClient.clear();
            }
            orphaned.forEach(DefaultClientFactory::shutdownQuietly);
        }

        /** Visible for testing: how many pooled HTTP clients are still alive. */
        int pooledClientCount() {
            synchronized (poolLock) {
                return pool.size();
            }
        }

        private java.net.http.HttpClient acquire(long maxResponseBytes) {
            synchronized (poolLock) {
                PooledHttpClient pooled = pool.get(maxResponseBytes);
                if (pooled == null) {
                    pooled = new PooledHttpClient(
                            adcpHttpClient.newHttpClientBuilder(maxResponseBytes).build());
                    pool.put(maxResponseBytes, pooled);
                }
                pooled.references++;
                return pooled.httpClient();
            }
        }

        private void release(long maxResponseBytes) {
            java.net.http.HttpClient unused = null;
            synchronized (poolLock) {
                PooledHttpClient pooled = pool.get(maxResponseBytes);
                if (pooled != null && --pooled.references <= 0) {
                    pool.remove(maxResponseBytes);
                    unused = pooled.httpClient();
                }
            }
            if (unused != null) {
                shutdownQuietly(unused);
            }
        }

        /**
         * {@code shutdownNow()} rather than {@code close()}: this can run under
         * the connection cache lock during LRU eviction, and {@code close()}
         * blocks until every in-flight exchange finishes. The owning A2A client
         * has already been closed by then, which matches the existing eviction
         * contract — an in-flight call on an evicted connection fails with an
         * IOException and {@code ProtocolClient} retries.
         */
        private static void shutdownQuietly(java.net.http.HttpClient httpClient) {
            try {
                httpClient.shutdownNow();
            } catch (RuntimeException e) {
                log.debug("Error shutting down pooled A2A HTTP client: {}",
                        sanitizeLogText(e.getMessage()));
            }
        }

        private static final class PooledHttpClient {
            private final java.net.http.HttpClient httpClient;
            private int references;

            PooledHttpClient(java.net.http.HttpClient httpClient) {
                this.httpClient = httpClient;
            }

            java.net.http.HttpClient httpClient() {
                return httpClient;
            }
        }
    }

    private static final class HttpAgentCardLoader implements AgentCardLoader {
        private final AdcpHttpClient adcpHttpClient;
        private final ObjectMapper objectMapper;

        private HttpAgentCardLoader(AdcpHttpClient adcpHttpClient, ObjectMapper objectMapper) {
            this.adcpHttpClient = Objects.requireNonNull(adcpHttpClient, "adcpHttpClient");
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
            this.objectMapper.deactivateDefaultTyping();
        }

        @Override
        public AgentCard load(AgentConfig agent, Map<String, String> headers,
                              long maxResponseBytes) {
            URI cardUri = buildAgentCardUri(agent.agentUri());
            try {
                // Use getForAgentCard so that auth headers (e.g. Authorization) are forwarded
                // to private agent-card endpoints. Redirect-following is already blocked by
                // the underlying HttpClient (followRedirects=NEVER) and SSRF validation is
                // enforced inside getForAgentCard, so header leakage is not possible.
                AdcpHttpResponse response = adcpHttpClient.getForAgentCard(
                        cardUri, headers, maxResponseBytes);
                if (response.statusCode() >= 200 && response.statusCode() < 300 && !response.truncated()) {
                    AgentCard parsed = objectMapper.readValue(response.body(), AgentCard.class);
                    return normalize(parsed, agent.agentUri());
                }
                log.debug("Falling back to synthetic A2A AgentCard for {} (status={}, truncated={})",
                        agent.agentUri(), response.statusCode(), response.truncated());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProtocolError("a2a", "Interrupted while fetching A2A agent card from " + cardUri, e);
            } catch (IOException e) {
                log.debug("Falling back to synthetic A2A AgentCard for {}: {}", agent.agentUri(), e.getMessage());
            }
            return fallbackCard(agent);
        }

        private static URI buildAgentCardUri(URI baseUri) {
            // The A2A spec default is /.well-known/agent-card.json on the origin root
            // (scheme + authority), not appended to the agent URI's path component.
            return URI.create(baseUri.getScheme() + "://" + baseUri.getAuthority() + "/.well-known/agent-card.json");
        }

        private static AgentCard normalize(AgentCard card, URI baseUri) {
            AgentCard.Builder builder = AgentCard.builder(card);
            // SECURITY (C-2): Always pin url and supportedInterfaces to the validated
            // baseUri, regardless of what the remote agent card declares. The agent-card
            // fetch was SSRF-validated; any URL the server embeds in its card is untrusted
            // and could redirect subsequent JSON-RPC calls to internal network addresses.
            builder.url(baseUri.toString());
            builder.supportedInterfaces(List.of(new AgentInterface(JSONRPC_TRANSPORT, baseUri.toString())));
            if (card.name() == null || card.name().isBlank()) {
                builder.name(baseUri.getHost() != null ? baseUri.getHost() : baseUri.toString());
            }
            if (card.description() == null || card.description().isBlank()) {
                builder.description("AdCP agent at " + baseUri);
            }
            if (card.version() == null || card.version().isBlank()) {
                builder.version("unknown");
            }
            if (card.preferredTransport() == null || card.preferredTransport().isBlank()) {
                builder.preferredTransport(JSONRPC_TRANSPORT);
            }
            if (card.capabilities() == null) {
                builder.capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build());
            }
            if (card.defaultInputModes() == null) {
                builder.defaultInputModes(List.of("text"));
            }
            if (card.defaultOutputModes() == null) {
                builder.defaultOutputModes(List.of("text"));
            }
            if (card.skills() == null) {
                builder.skills(List.of());
            }
            return builder.build();
        }

        private static AgentCard fallbackCard(AgentConfig agent) {
            String version = agent.adcpVersion() != null && agent.adcpVersion().minorVersion() != null
                    ? agent.adcpVersion().minorVersion()
                    : agent.adcpVersion() != null
                    ? String.valueOf(agent.adcpVersion().majorVersion())
                    : "unknown";
            return AgentCard.builder()
                    .name(agent.id())
                    .description("AdCP agent " + agent.id())
                    .version(version)
                    .url(agent.agentUri().toString())
                    .preferredTransport(JSONRPC_TRANSPORT)
                    .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
                    .supportedInterfaces(List.of(new AgentInterface(JSONRPC_TRANSPORT, agent.agentUri().toString())))
                    .defaultInputModes(List.of("text"))
                    .defaultOutputModes(List.of("text"))
                    .skills(List.of())
                    .build();
        }
    }
}
