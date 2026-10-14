package org.adcontextprotocol.adcp.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.adcontextprotocol.adcp.error.ResponseTooLargeError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Body-cap behaviour of {@link AdcpHttpClient} against a live loopback server.
 *
 * <p>Two caps, two behaviours: the probe paths ({@code send}/{@code get}/
 * {@code post}) stop reading at the small probe cap and flag the result,
 * because they only ever look at the status line and headers. The agent-card
 * path parses what it gets, so it is held to the strict call cap and throws
 * instead of handing back a half-read card.
 */
class AdcpHttpClientBodyCapTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);

        server.createContext("/chunked", exchange -> {
            int bytes = intParam(exchange, "bytes");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            writeFiller(exchange, bytes);
        });

        server.createContext("/declared", exchange -> {
            int bytes = intParam(exchange, "bytes");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes);
            writeFiller(exchange, bytes);
        });

        server.createContext("/.well-known/agent-card.json", exchange -> {
            byte[] body = ("{\"name\":\"a\",\"description\":\"d\",\"version\":\"1\","
                    + "\"url\":\"http://127.0.0.1/\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        // Sends headers, then one byte per `delayMs`, forever.
        server.createContext("/drip", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                while (true) {
                    os.write('x');
                    os.flush();
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException expectedWhenClientGivesUp) {
                // the client hit its read deadline
            }
        });

        // Sends headers and then nothing at all.
        server.createContext("/stall", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
            try {
                exchange.getResponseBody().close();
            } catch (IOException ignored) {
                // client already gone
            }
        });

        server.createContext("/echo-accept-encoding", exchange -> {
            byte[] body = String.valueOf(
                            exchange.getRequestHeaders().getFirst("Accept-Encoding"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private AdcpHttpClient client() {
        return AdcpHttpClient.builder()
                .ssrfPolicy(SsrfPolicy.permissive())
                .maxResponseBytes(4096)
                .maxCallResponseBytes(16 * 1024)
                .build();
    }

    @Test
    void probe_response_over_the_probe_cap_is_flagged_not_thrown() throws Exception {
        try (AdcpHttpClient client = client()) {
            AdcpHttpResponse response =
                    client.get(URI.create(base + "/chunked?bytes=20000"), Map.of());

            assertTrue(response.truncated(),
                    "probe callers must be able to see that the body is incomplete");
            assertEquals(4096, response.body().length);
        }
    }

    @Test
    void agent_card_over_the_call_cap_throws() {
        try (AdcpHttpClient client = client()) {
            ResponseTooLargeError error = assertThrows(ResponseTooLargeError.class,
                    () -> client.getForAgentCard(
                            URI.create(base + "/chunked?bytes=" + (64 * 1024)), Map.of()));

            assertEquals(16 * 1024, error.limit());
            assertTrue(error.bytesRead() > 16 * 1024);
        }
    }

    @Test
    void agent_card_with_an_over_cap_content_length_is_rejected_before_reading() {
        try (AdcpHttpClient client = client()) {
            ResponseTooLargeError error = assertThrows(ResponseTooLargeError.class,
                    () -> client.getForAgentCard(
                            URI.create(base + "/declared?bytes=" + (64 * 1024)), Map.of()));

            assertEquals(16 * 1024, error.limit());
            assertEquals(64 * 1024, error.bytesRead(),
                    "the declared length should be reported verbatim — nothing was read");
        }
    }

    @Test
    void agent_card_under_the_call_cap_is_returned_whole() throws Exception {
        try (AdcpHttpClient client = client()) {
            AdcpHttpResponse response = client.getForAgentCard(
                    URI.create(base + "/.well-known/agent-card.json"), Map.of());

            assertEquals(200, response.statusCode());
            assertFalse(response.truncated());
            assertTrue(response.bodyAsString().contains("\"name\":\"a\""));
        }
    }

    @Test
    void agent_card_between_the_probe_cap_and_the_call_cap_still_succeeds() throws Exception {
        // A 4 KiB probe cap would have silently truncated this card and sent the
        // A2A loader off to a synthetic fallback; the 16 KiB call cap must not.
        try (AdcpHttpClient client = client()) {
            AdcpHttpResponse response = client.getForAgentCard(
                    URI.create(base + "/declared?bytes=10000"), Map.of());

            assertFalse(response.truncated());
            assertEquals(10000, response.body().length);
        }
    }

    @Test
    void outbound_requests_force_identity_encoding() throws Exception {
        try (AdcpHttpClient client = client()) {
            URI uri = URI.create(base + "/echo-accept-encoding");

            assertEquals("identity",
                    client.get(uri, Map.of("Accept-Encoding", "gzip")).bodyAsString());
            assertEquals("identity",
                    client.getForAgentCard(uri, Map.of("Accept-Encoding", "gzip"))
                            .bodyAsString());
        }
    }

    @Test
    void a_dripping_peer_cannot_hold_the_calling_thread_past_the_read_timeout() {
        // HttpRequest.timeout() only covers the status line and headers. Without
        // a body deadline, a peer that sends headers and then drips one byte at
        // a time pins this thread and its socket for as long as it takes to
        // reach the cap — at 4 MiB, weeks.
        try (AdcpHttpClient client = AdcpHttpClient.builder()
                .ssrfPolicy(SsrfPolicy.permissive())
                .readTimeout(Duration.ofMillis(600))
                .build()) {
            URI uri = URI.create(base + "/drip");
            long start = System.nanoTime();

            assertThrows(IOException.class, () -> client.getForAgentCard(uri, Map.of()));

            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 20,
                    "the read must give up at the deadline, not run to the 4 MiB cap");
        }
    }

    @Test
    void a_stalled_peer_cannot_hold_the_calling_thread_either() {
        // Nothing arrives at all, so an in-loop deadline check never runs — the
        // watchdog has to close the stream out from under the blocked read.
        try (AdcpHttpClient client = AdcpHttpClient.builder()
                .ssrfPolicy(SsrfPolicy.permissive())
                .readTimeout(Duration.ofMillis(600))
                .build()) {
            URI uri = URI.create(base + "/stall");
            long start = System.nanoTime();

            assertThrows(IOException.class, () -> client.getForAgentCard(uri, Map.of()));

            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 20,
                    "a stalled body read must not block forever");
        }
    }

    @Test
    void agent_card_cap_can_be_overridden_per_fetch() throws Exception {
        // An agent configured for a bigger cap must get it on discovery too,
        // not just on the call — otherwise its connection dies on the card.
        try (AdcpHttpClient client = client()) {
            URI uri = URI.create(base + "/declared?bytes=" + (32 * 1024));

            assertThrows(ResponseTooLargeError.class,
                    () -> client.getForAgentCard(uri, Map.of()));

            AdcpHttpResponse response = client.getForAgentCard(uri, Map.of(), 64 * 1024);
            assertEquals(32 * 1024, response.body().length);
        }
    }

    @Test
    void agent_card_fetch_rejects_a_non_positive_cap() {
        try (AdcpHttpClient client = client()) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.getForAgentCard(URI.create(base + "/chunked?bytes=1"),
                            Map.of(), 0));
        }
    }

    @Test
    void probe_and_call_caps_have_independent_defaults() {
        try (AdcpHttpClient client = AdcpHttpClient.builder().build()) {
            assertEquals(AdcpHttpClient.DEFAULT_MAX_RESPONSE_BYTES, client.maxResponseBytes());
            assertEquals(AdcpHttpClient.DEFAULT_MAX_CALL_RESPONSE_BYTES,
                    client.maxCallResponseBytes());
            assertEquals(4 * 1024, client.maxResponseBytes());
            assertEquals(4 * 1024 * 1024, client.maxCallResponseBytes());
        }
    }

    private static void writeFiller(HttpExchange exchange, int bytes) throws IOException {
        byte[] body = new byte[bytes];
        Arrays.fill(body, (byte) 'x');
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        } catch (IOException expectedWhenCapAborts) {
            // the client stopped reading
        }
    }

    private static int intParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return 0;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return Integer.parseInt(kv[1]);
            }
        }
        return 0;
    }
}
