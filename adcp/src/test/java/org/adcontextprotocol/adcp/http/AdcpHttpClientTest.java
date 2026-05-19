package org.adcontextprotocol.adcp.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdcpHttpClient} focusing on SSRF protections,
 * body capping, and redirect behavior.
 *
 * <p>These tests validate the API contract without making real network
 * calls where possible. Live HTTP tests are deferred to integration tests.
 */
class AdcpHttpClientTest {

    @Test
    void builder_defaults_to_strict_ssrf_policy() {
        AdcpHttpClient client = AdcpHttpClient.builder().build();
        assertSame(SsrfPolicy.strict(), client.ssrfPolicy());
    }

    @Test
    void builder_sets_max_response_bytes() {
        AdcpHttpClient client = AdcpHttpClient.builder()
                .maxResponseBytes(1024)
                .build();
        assertEquals(1024, client.maxResponseBytes());
    }

    @Test
    void builder_rejects_non_positive_max_response_bytes() {
        assertThrows(IllegalArgumentException.class,
                () -> AdcpHttpClient.builder().maxResponseBytes(0));
        assertThrows(IllegalArgumentException.class,
                () -> AdcpHttpClient.builder().maxResponseBytes(-1));
    }

    @Test
    void builder_accepts_permissive_policy() {
        AdcpHttpClient client = AdcpHttpClient.builder()
                .ssrfPolicy(SsrfPolicy.permissive())
                .build();
        assertSame(SsrfPolicy.permissive(), client.ssrfPolicy());
    }

    @Test
    void send_rejects_null_uri() {
        AdcpHttpClient client = AdcpHttpClient.builder().build();
        assertThrows(NullPointerException.class,
                () -> client.send("GET", null, Map.of(), null));
    }

    @Test
    void send_blocks_loopback_with_strict_policy() {
        AdcpHttpClient client = AdcpHttpClient.builder().build();
        assertThrows(SsrfBlockedException.class,
                () -> client.get(URI.create("http://127.0.0.1/test"), Map.of()));
    }

    @Test
    void send_blocks_metadata_endpoint_with_strict_policy() {
        AdcpHttpClient client = AdcpHttpClient.builder().build();
        assertThrows(SsrfBlockedException.class,
                () -> client.get(
                        URI.create("http://169.254.169.254/latest/meta-data/"),
                        Map.of()));
    }

    @Test
    void send_blocks_rfc1918_with_strict_policy() {
        AdcpHttpClient client = AdcpHttpClient.builder().build();
        assertThrows(SsrfBlockedException.class,
                () -> client.get(URI.create("http://10.0.0.1/admin"), Map.of()));
        assertThrows(SsrfBlockedException.class,
                () -> client.get(URI.create("http://192.168.1.1/"), Map.of()));
    }

    @Test
    void default_max_response_bytes_is_4kb() {
        assertEquals(4096, AdcpHttpClient.DEFAULT_MAX_RESPONSE_BYTES);
    }

    @Test
    void client_is_autocloseable() {
        // Verify AdcpHttpClient implements AutoCloseable
        try (AdcpHttpClient client = AdcpHttpClient.builder().build()) {
            assertNotNull(client);
        }
    }

    @Test
    void requireHttps_rejects_plain_http_for_remote_hosts() {
        AdcpHttpClient client = AdcpHttpClient.builder()
                .ssrfPolicy(SsrfPolicy.permissive())
                .requireHttps(true)
                .build();
        IOException ex = assertThrows(IOException.class,
                () -> client.get(URI.create("http://agent.example.com/mcp"), Map.of()));
        assertTrue(ex.getMessage().contains("requireHttps"),
                "Error should mention requireHttps: " + ex.getMessage());
    }

    @Test
    void requireHttps_allows_localhost_http() {
        // Localhost is exempt from requireHttps for local development.
        // We verify the requireHttps check passes; downstream errors
        // (connection refused, restricted headers, etc.) are expected.
        AdcpHttpClient client = AdcpHttpClient.builder()
                .ssrfPolicy(SsrfPolicy.permissive())
                .requireHttps(true)
                .build();
        try {
            client.get(URI.create("http://localhost:4500/mcp"), Map.of());
            // If it succeeds (unlikely in test env), that's fine too
        } catch (Exception e) {
            // Walk the exception chain — requireHttps rejection must NOT appear
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertFalse(
                        t.getMessage() != null && t.getMessage().contains("requireHttps"),
                        "Localhost should be exempt from requireHttps: " + t.getMessage());
            }
        }
    }

    @Test
    void requireHttps_defaults_to_false() {
        // Default behavior should not block http:// via requireHttps
        AdcpHttpClient client = AdcpHttpClient.builder()
                .ssrfPolicy(SsrfPolicy.permissive())
                .build();
        try {
            client.get(URI.create("http://agent.example.com/mcp"), Map.of());
        } catch (Exception e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertFalse(
                        t.getMessage() != null && t.getMessage().contains("requireHttps"),
                        "requireHttps should default to false: " + t.getMessage());
            }
        }
    }

    @Test
    void send_rejects_octal_ip_literal() {
        AdcpHttpClient client = AdcpHttpClient.builder()
                .ssrfPolicy(SsrfPolicy.permissive())
                .build();
        assertThrows(SsrfBlockedException.class,
                () -> client.get(URI.create("http://0177.0.0.1/test"), Map.of()));
    }
}
