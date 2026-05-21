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
import org.adcontextprotocol.adcp.http.AdcpHttpClient;
import org.adcontextprotocol.adcp.http.AdcpHttpResponse;
import org.adcontextprotocol.adcp.http.ProtectedHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages cached A2A clients keyed by agent URL, credential cache hash, and
 * non-secret sanitized discovery headers.
 *
 * <p>Headers are included in the cache key because agent-card discovery is
 * header-sensitive. The separate cache hash isolates clients by credentials
 * without storing raw secrets in the cache key.
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
    private volatile boolean closed;

    public A2aConnectionManager(AdcpHttpClient adcpHttpClient, ObjectMapper objectMapper) {
        this(new HttpAgentCardLoader(adcpHttpClient, objectMapper), new DefaultClientFactory(adcpHttpClient));
    }

    A2aConnectionManager(AgentCardLoader agentCardLoader, ClientFactory clientFactory) {
        this.agentCardLoader = Objects.requireNonNull(agentCardLoader, "agentCardLoader");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.connectStripes = new Semaphore[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            connectStripes[i] = new Semaphore(1);
        }
    }

    public Client getOrConnect(AgentConfig agent, Map<String, String> headers, String cacheHash) {
        if (closed) {
            throw new IllegalStateException("A2aConnectionManager is closed");
        }
        Objects.requireNonNull(cacheHash, "cacheHash");
        Objects.requireNonNull(headers, "headers");
        Map<String, String> sanitizedAll = sanitizeHeaders(headers);
        Map<String, String> sanitizedForKey = filterProtected(sanitizedAll);
        String cacheKey = buildCacheKey(agent.agentUri(), sanitizedForKey, cacheHash);

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

            Client client = connect(agent, sanitizedAll);

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
        String prefix = agentUri + "#" + cacheHash;
        // Evicts all cache entries for the given agent URI and credential hash,
        // regardless of which non-secret discovery headers they used.
        // This is intentional: on a transport error, all variants for those
        // credentials are assumed stale.
        evictMatching(key -> key.equals(prefix) || key.startsWith(prefix + "?"));
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
    }

    private Client connect(AgentConfig agent, Map<String, String> sanitizedHeaders) {
        try {
            AgentCard card = agentCardLoader.load(agent, sanitizedHeaders);
            return clientFactory.create(card);
        } catch (ProtocolError e) {
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
        StringBuilder sb = new StringBuilder(agentUri.toString())
                .append('#')
                .append(cacheHash);
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
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            log.debug("Error closing A2A client: {}", sanitizeLogText(e.getMessage()));
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
        AgentCard load(AgentConfig agent, Map<String, String> headers);
    }

    interface ClientFactory {
        Client create(AgentCard agentCard) throws A2AClientException;
    }

    private static final class DefaultClientFactory implements ClientFactory {
        /**
         * SSRF-safe HTTP client used by the A2A JSON-RPC transport.
         * Backed by the same {@link java.net.http.HttpClient} that was built with
         * {@code followRedirects(NEVER)} and the configured connect timeout, so
         * the transport cannot follow HTTP redirects to internal addresses.
         * Combined with {@link HttpAgentCardLoader#normalize} pinning AgentCard
         * URLs to the validated agent URI, this closes the SSRF bypass that would
         * otherwise exist in the default {@code JdkA2AHttpClient} (which uses
         * {@code Redirect.NORMAL}).
         */
        private final org.a2aproject.sdk.client.http.A2AHttpClient safeHttpClient;

        DefaultClientFactory(AdcpHttpClient adcpHttpClient) {
            this.safeHttpClient = new JdkA2AHttpClient(
                    adcpHttpClient.newHttpClientBuilder().build());
        }

        @Override
        public Client create(AgentCard agentCard) throws A2AClientException {
            ClientConfig config = ClientConfig.builder()
                    .setStreaming(true)
                    .setUseClientPreference(true)
                    .build();
            return Client.builder(agentCard)
                    .clientConfig(config)
                    .withTransport(JSONRPCTransport.class,
                            new JSONRPCTransportConfigBuilder().httpClient(safeHttpClient))
                    .build();
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
        public AgentCard load(AgentConfig agent, Map<String, String> headers) {
            URI cardUri = buildAgentCardUri(agent.agentUri());
            try {
                AdcpHttpResponse response = adcpHttpClient.get(cardUri, headers);
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
            // The A2A Agent Card is always at /.well-known/agent.json on the origin root
            // (scheme + authority), not appended to the agent URI's path component.
            return URI.create(baseUri.getScheme() + "://" + baseUri.getAuthority() + "/.well-known/agent.json");
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
