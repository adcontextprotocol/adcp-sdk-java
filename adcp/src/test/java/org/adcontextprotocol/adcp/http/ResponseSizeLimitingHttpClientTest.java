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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the streaming response-size cap that protects every MCP and A2A call
 * from a memory-DoS by an oversized agent response.
 *
 * <p>Each test drives a real loopback HTTP server through a real JDK
 * {@link HttpClient}, because the whole point of the cap is that it fires
 * <em>while the body streams in</em> — a check that only runs after the body is
 * buffered would pass a unit test and still OOM the caller.
 *
 * <p>Remove the cap and these fail: the oversized-body cases return a body
 * instead of throwing.
 */
class ResponseSizeLimitingHttpClientTest {

    private static final int CAP = 64 * 1024;

    private HttpServer server;
    private ExecutorService serverExecutor;
    private HttpClient raw;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);

        // Chunked body of `bytes` bytes, no Content-Length.
        server.createContext("/chunked", exchange -> {
            long bytes = longParam(exchange, "bytes", 0);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            writeBytes(exchange, bytes);
        });

        // Body of `bytes` bytes with a declared Content-Length.
        server.createContext("/declared", exchange -> {
            long bytes = longParam(exchange, "bytes", 0);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes);
            writeBytes(exchange, bytes);
        });

        // `events` SSE events of `size` payload bytes each.
        server.createContext("/sse", exchange -> {
            long events = longParam(exchange, "events", 1);
            long size = longParam(exchange, "size", 16);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String eol = switch (stringParam(exchange, "eol", "lf")) {
                case "cr" -> "\r";
                case "crlf" -> "\r\n";
                default -> "\n";
            };
            byte[] filler = new byte[(int) size];
            Arrays.fill(filler, (byte) 'x');
            try (OutputStream os = exchange.getResponseBody()) {
                for (long i = 0; i < events; i++) {
                    os.write("data: ".getBytes(StandardCharsets.UTF_8));
                    os.write(filler);
                    os.write((eol + eol).getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException expectedWhenCapAborts) {
                // the client cancelled mid-stream
            }
        });

        // Echoes method, body, a custom header and Accept-Encoding.
        server.createContext("/echo-request", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            byte[] body = (exchange.getRequestMethod()
                    + "|" + requestBody
                    + "|" + exchange.getRequestHeaders().getFirst("X-Custom")
                    + "|" + exchange.getRequestHeaders().getFirst("Accept-Encoding"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        // Echoes the request's Accept-Encoding header back as the body.
        server.createContext("/accept-encoding", exchange -> {
            byte[] body = String.valueOf(
                            exchange.getRequestHeaders().getFirst("Accept-Encoding"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        raw = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        raw.close();
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    // -- the cap itself --

    @Test
    void over_cap_body_without_content_length_throws() {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        IOException thrown = assertThrows(IOException.class,
                () -> get(client, "/chunked?bytes=" + (CAP * 4L)));

        ResponseTooLargeError tooLarge = ResponseTooLargeError.findIn(thrown);
        assertNotNull(tooLarge, "expected a ResponseTooLargeError in the cause chain");
        assertEquals(CAP, tooLarge.limit());
        assertTrue(tooLarge.bytesRead() > CAP,
                "bytesRead should report the count at the moment of the abort");
    }

    @Test
    void over_cap_content_length_is_rejected_before_the_body_is_read() {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);
        long declared = CAP * 4L;

        IOException thrown = assertThrows(IOException.class,
                () -> get(client, "/declared?bytes=" + declared));

        ResponseTooLargeError tooLarge = ResponseTooLargeError.findIn(thrown);
        assertNotNull(tooLarge);
        assertEquals(CAP, tooLarge.limit());
        // The declared length is reported verbatim — proof that nothing was read.
        assertEquals(declared, tooLarge.bytesRead());
    }

    @Test
    void under_cap_body_succeeds() throws Exception {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        HttpResponse<String> response = get(client, "/chunked?bytes=" + (CAP / 2));

        assertEquals(200, response.statusCode());
        assertEquals(CAP / 2, response.body().length());
    }

    @Test
    void body_exactly_at_the_cap_succeeds() throws Exception {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        HttpResponse<String> response = get(client, "/declared?bytes=" + CAP);

        assertEquals(CAP, response.body().length());
    }

    @Test
    void per_call_override_raises_the_cap() throws Exception {
        long body = CAP * 2L;
        HttpClient capped = ResponseSizeLimitingHttpClient.wrap(raw, CAP);
        assertThrows(IOException.class, () -> get(capped, "/chunked?bytes=" + body));

        HttpClient raised = ResponseSizeLimitingHttpClient.wrap(raw, body * 2);
        assertEquals(body, get(raised, "/chunked?bytes=" + body).body().length());
    }

    @Test
    void async_sends_are_capped_too() {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        CompletableFuture<HttpResponse<String>> future = client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/chunked?bytes=" + (CAP * 4L))).build(),
                HttpResponse.BodyHandlers.ofString());

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(30, TimeUnit.SECONDS));
        assertNotNull(ResponseTooLargeError.findIn(thrown));
    }

    // -- SSE --

    @Test
    void single_over_cap_sse_event_throws() {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        // One event, four times the cap. A GET SSE stream has no cumulative
        // cap, so only the per-event cap can catch this.
        IOException thrown = assertThrows(IOException.class,
                () -> get(client, "/sse?events=1&size=" + (CAP * 4L)));

        ResponseTooLargeError tooLarge = ResponseTooLargeError.findIn(thrown);
        assertNotNull(tooLarge, "a single oversized SSE event must not escape the cap");
        assertEquals(CAP, tooLarge.limit());
    }

    @Test
    void cumulative_sse_over_cap_throws_for_a_single_call() {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        // Eight events, each an eighth of the cap under it: no single event
        // trips the per-event cap, but together they are over.
        long size = CAP / 4;
        IOException thrown = assertThrows(IOException.class,
                () -> post(client, "/sse?events=8&size=" + size));

        ResponseTooLargeError tooLarge = ResponseTooLargeError.findIn(thrown);
        assertNotNull(tooLarge,
                "a call answered as a drip-feed of small SSE events must not escape the cap");
        assertEquals(CAP, tooLarge.limit());
    }

    @Test
    void sse_content_type_does_not_bypass_the_cap_on_a_call() {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        // A peer answering a POST with text/event-stream instead of
        // application/json gets no extra budget.
        assertThrows(IOException.class,
                () -> post(client, "/sse?events=1&size=" + (CAP * 4L)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"lf", "cr", "crlf"})
    void event_boundaries_reset_the_per_event_counter_for_every_sse_line_terminator(String eol)
            throws Exception {
        // SSE accepts CR, LF and CRLF as line terminators, and the JDK's
        // fromLineSubscriber — which both transports sit behind — splits on all
        // three. Missing one makes the per-event counter run on forever and
        // kills a conforming peer's stream with a bogus ResponseTooLargeError.
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        HttpResponse<String> response =
                get(client, "/sse?events=8&size=" + (CAP / 4) + "&eol=" + eol);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().length() > CAP,
                "8 events of a quarter-cap each is twice the cap in total");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"lf", "cr", "crlf"})
    void a_single_over_cap_event_is_caught_for_every_line_terminator(String eol) {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        IOException thrown = assertThrows(IOException.class,
                () -> get(client, "/sse?events=1&size=" + (CAP * 4L) + "&eol=" + eol));

        assertNotNull(ResponseTooLargeError.findIn(thrown));
    }

    @Test
    void long_lived_get_sse_stream_still_has_a_finite_cumulative_ceiling() {
        // The listening-stream exemption is triggered by the peer's own
        // Content-Type, so it must not mean "unbounded": a peer answering a GET
        // with text/event-stream and a forever-stream of small events still
        // gets torn down.
        long tinyCap = 2048;
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, tinyCap);
        long ceiling = tinyCap * ResponseSizeLimitingHttpClient.LISTENING_STREAM_MULTIPLIER;
        long events = (ceiling / 512) + 16;

        IOException thrown = assertThrows(IOException.class,
                () -> get(client, "/sse?events=" + events + "&size=500"));

        ResponseTooLargeError tooLarge = ResponseTooLargeError.findIn(thrown);
        assertNotNull(tooLarge, "a listening stream must still have a finite ceiling");
        assertEquals(ceiling, tooLarge.limit());
    }

    @Test
    void long_lived_get_sse_stream_is_capped_per_event_not_cumulatively() throws Exception {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        // The MCP SSE session channel is a GET that stays open for the life of
        // the connection and carries every server-to-client message. Capping
        // its running total would kill healthy long sessions, so only the
        // per-event cap applies: 8 events of a quarter-cap each (twice the cap
        // in total) must stream through.
        HttpResponse<String> response = get(client, "/sse?events=8&size=" + (CAP / 4));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().length() > CAP,
                "the listening stream should be allowed past the cumulative cap");
        assertEquals(8, response.body().split("\n\n", -1).length - 1);
    }

    // -- encoding --

    @Test
    void outbound_requests_ask_for_identity_encoding() throws Exception {
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        // Counting compressed bytes would let a peer hide a huge payload behind
        // a small gzip stream, so a caller-supplied Accept-Encoding is replaced.
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/accept-encoding"))
                        .header("Accept-Encoding", "gzip, deflate")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals("identity", response.body());
    }

    // -- plumbing --

    @Test
    void rewriting_accept_encoding_preserves_the_request_body_and_method() throws Exception {
        // The MCP and A2A transports POST JSON-RPC payloads; the Accept-Encoding
        // rewrite rebuilds the request, and dropping the body there would break
        // every call while the cap tests still passed.
        HttpClient client = ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/echo-request"))
                        .header("Accept-Encoding", "gzip")
                        .header("X-Custom", "kept")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals("POST|{\"jsonrpc\":\"2.0\"}|kept|identity", response.body());
    }

    @Test
    void wrap_rejects_a_non_positive_cap() {
        assertThrows(IllegalArgumentException.class,
                () -> ResponseSizeLimitingHttpClient.wrap(raw, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseSizeLimitingHttpClient.wrap(raw, -1));
    }

    @Test
    void wrapper_exposes_the_delegate_configuration() {
        ResponseSizeLimitingHttpClient client =
                ResponseSizeLimitingHttpClient.wrap(raw, CAP);

        assertEquals(CAP, client.maxResponseBytes());
        assertEquals(raw.followRedirects(), client.followRedirects());
        assertEquals(raw.version(), client.version());
        assertEquals(raw.connectTimeout(), client.connectTimeout());
    }

    @Test
    void adcp_http_client_builds_capped_transport_clients() {
        AdcpHttpClient adcp = AdcpHttpClient.builder()
                .maxCallResponseBytes(123_456)
                .build();
        try (adcp) {
            HttpClient mcp = adcp.newMcpClientBuilder().build();
            HttpClient a2a = adcp.newHttpClientBuilder().build();
            HttpClient override = adcp.newHttpClientBuilder(999).build();
            try (mcp; a2a; override) {
                assertEquals(123_456,
                        ((ResponseSizeLimitingHttpClient) mcp).maxResponseBytes());
                assertEquals(123_456,
                        ((ResponseSizeLimitingHttpClient) a2a).maxResponseBytes());
                assertEquals(999,
                        ((ResponseSizeLimitingHttpClient) override).maxResponseBytes());
                assertEquals(HttpClient.Redirect.NEVER, mcp.followRedirects());
            }
        }
    }

    @Test
    void adcp_http_client_rejects_a_non_positive_transport_cap() {
        try (AdcpHttpClient adcp = AdcpHttpClient.builder().build()) {
            assertThrows(IllegalArgumentException.class,
                    () -> adcp.newHttpClientBuilder(0));
            assertThrows(IllegalArgumentException.class,
                    () -> AdcpHttpClient.builder().maxCallResponseBytes(-1));
        }
    }

    @Test
    void error_message_redacts_credentials_in_the_url() {
        URI withSecrets = URI.create("https://svc:s3cr3t@agent.example.com/mcp?api_key=abc123");
        ResponseTooLargeError error = new ResponseTooLargeError(10, 20, withSecrets);

        assertFalse(error.getMessage().contains("s3cr3t"),
                "userinfo must not reach a message that gets logged and shown to users");
        assertFalse(error.getMessage().contains("abc123"),
                "query strings carry API keys");
        assertTrue(error.getMessage().contains("agent.example.com/mcp"));
        assertEquals(withSecrets, error.url(), "url() still returns what was configured");
    }

    @Test
    void find_in_walks_cause_and_suppressed_chains_without_looping() {
        ResponseTooLargeError error = new ResponseTooLargeError(1, 2, null);

        assertNull(ResponseTooLargeError.findIn(null));
        assertEquals(error, ResponseTooLargeError.findIn(error));
        assertEquals(error,
                ResponseTooLargeError.findIn(new IOException("wrapped", error)));

        IOException suppressing = new IOException("outer");
        suppressing.addSuppressed(error);
        assertEquals(error, ResponseTooLargeError.findIn(suppressing));

        // A cyclic cause chain must terminate rather than spin forever.
        IOException first = new IOException("a");
        IOException second = new IOException("b");
        first.initCause(second);
        second.initCause(first);
        assertNull(ResponseTooLargeError.findIn(first));
    }

    // -- helpers --

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void writeBytes(HttpExchange exchange, long bytes) throws IOException {
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'x');
        try (OutputStream os = exchange.getResponseBody()) {
            long written = 0;
            while (written < bytes) {
                int n = (int) Math.min(chunk.length, bytes - written);
                os.write(chunk, 0, n);
                written += n;
            }
        } catch (IOException expectedWhenCapAborts) {
            // the client cancelled mid-stream
        }
    }

    private static String stringParam(HttpExchange exchange, String name, String fallback) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return kv[1];
            }
        }
        return fallback;
    }

    private static long longParam(HttpExchange exchange, String name, long fallback) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            List<String> kv = Arrays.asList(pair.split("=", 2));
            if (kv.size() == 2 && kv.get(0).equals(name)) {
                return Long.parseLong(kv.get(1));
            }
        }
        return fallback;
    }
}
