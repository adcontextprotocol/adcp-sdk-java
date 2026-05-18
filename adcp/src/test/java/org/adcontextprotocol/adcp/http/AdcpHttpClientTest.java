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
}
