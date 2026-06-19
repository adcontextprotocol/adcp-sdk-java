package org.adcontextprotocol.adcp.server.signing.jwks;

import com.sun.net.httpserver.HttpServer;
import org.adcontextprotocol.adcp.http.AdcpHttpClient;
import org.adcontextprotocol.adcp.http.SsrfPolicy;
import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SignedInput;
import org.adcontextprotocol.adcp.signing.VerificationInput;
import org.adcontextprotocol.adcp.signing.VerificationKeyLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrandJsonJwksResolverTest {

    private HttpServer server;
    private int port;
    private AdcpHttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        httpClient = AdcpHttpClient.builder()
                .ssrfPolicy(SsrfPolicy.permissive())
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void uriRotation_createsNewCachingResolver() throws Exception {
        String jwksJson1 = """
                {
                  "keys": [{
                    "kid": "key-v1",
                    "kty": "OKP",
                    "crv": "Ed25519",
                    "alg": "EdDSA",
                    "use": "sig",
                    "key_ops": ["verify"],
                    "adcp_use": "adcp_whk",
                    "x": "y7tTfeqazsFeTn3ccCzQlcJ4qFWuYsu-JkJAcfc9VoA"
                  }]
                }
                """;

        String jwksJson2 = """
                {
                  "keys": [{
                    "kid": "key-v2",
                    "kty": "OKP",
                    "crv": "Ed25519",
                    "alg": "EdDSA",
                    "use": "sig",
                    "key_ops": ["verify"],
                    "adcp_use": "adcp_whk",
                    "x": "VgpQd9JRrBf433BcMw6IUNW7tHnAAHAHegsQ5U9I53c"
                  }]
                }
                """;

        String brandJsonV1 = """
                {
                  "agents": [{
                    "type": "buying",
                    "url": "http://127.0.0.1:%d/agent",
                    "jwks_uri": "http://127.0.0.1:%d/jwks-v1"
                  }]
                }
                """.formatted(port, port);

        String brandJsonV2 = """
                {
                  "agents": [{
                    "type": "buying",
                    "url": "http://127.0.0.1:%d/agent",
                    "jwks_uri": "http://127.0.0.1:%d/jwks-v2"
                  }]
                }
                """.formatted(port, port);

        final int[] callCount = {0};
        server.createContext("/brand.json", exchange -> {
            callCount[0]++;
            String body = callCount[0] <= 1 ? brandJsonV1 : brandJsonV2;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/jwks-v1", exchange -> {
            byte[] bytes = jwksJson1.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/jwks-v2", exchange -> {
            byte[] bytes = jwksJson2.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();

        BrandJsonJwksResolver resolver = new BrandJsonJwksResolver(
                "http://127.0.0.1:" + port + "/brand.json",
                "buying", null, null, httpClient, Duration.ofMillis(1));

        VerificationInput input1 = new VerificationInput(
                AdcpUse.WEBHOOK_SIGNING,
                "key-v1",
                new SignedInput("{}".getBytes(StandardCharsets.UTF_8), Map.of(), "POST", "/")
        );

        VerificationKeyLookup result1 = resolver.resolve(input1);
        assertInstanceOf(VerificationKeyLookup.Found.class, result1);
        assertEquals("http://127.0.0.1:" + port + "/jwks-v1", resolver.jwksUri());

        Thread.sleep(10);

        VerificationInput input2 = new VerificationInput(
                AdcpUse.WEBHOOK_SIGNING,
                "key-v2",
                new SignedInput("{}".getBytes(StandardCharsets.UTF_8), Map.of(), "POST", "/")
        );

        VerificationKeyLookup result2 = resolver.resolve(input2);
        assertInstanceOf(VerificationKeyLookup.Found.class, result2);
        assertEquals("http://127.0.0.1:" + port + "/jwks-v2", resolver.jwksUri());
    }
}