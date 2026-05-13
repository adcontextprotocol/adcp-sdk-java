package org.adcontextprotocol.adcp.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storyboard CI gate shell (per D8).
 *
 * <p>Asserts the Java SDK can reach the {@code @adcp/sdk/mock-server} sidecar
 * that CI boots before running tests. Today this is the only assertion;
 * as the {@code testing} track lands, this test grows into the real
 * storyboard runner driving {@code StoryboardRunner.run(...)}.
 *
 * <p>Skipped when {@code ADCP_MOCK_SERVER_URL} is unset (i.e. when running
 * tests locally without the sidecar). CI sets it via the workflow's
 * service-container step.
 */
@EnabledIfEnvironmentVariable(
        named = "ADCP_MOCK_SERVER_URL",
        matches = ".+",
        disabledReason = "Set ADCP_MOCK_SERVER_URL (e.g. http://localhost:4500) to run; CI sets it automatically"
)
class MockServerReachableTest {

    @Test
    void mock_server_is_reachable() throws Exception {
        String base = System.getenv("ADCP_MOCK_SERVER_URL");
        assertNotNull(base, "ADCP_MOCK_SERVER_URL not set");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // The mock-server exposes a health/root path; we don't pin the
        // path shape yet (it evolves with @adcp/sdk versions). Any
        // 2xx/3xx/4xx response proves the server is up.
        HttpRequest req = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(base + "/"))
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() < 500,
                "expected mock-server to respond <500; got " + resp.statusCode());
    }
}
