package org.adcontextprotocol.adcp.error;

import org.adcontextprotocol.adcp.auth.AuthChallengeInfo;
import org.adcontextprotocol.adcp.auth.OAuthMetadataInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link AdcpError} sealed hierarchy.
 */
class AdcpErrorTest {

    @Test
    void protocolError_carries_protocol_and_cause() {
        var cause = new RuntimeException("transport failed");
        var error = new ProtocolError("mcp", "MCP call failed", cause);

        assertEquals("PROTOCOL_ERROR", error.code());
        assertEquals("mcp", error.protocol());
        assertSame(cause, error.getCause());
    }

    @Test
    void authenticationRequiredError_carries_challenge() {
        var challenge = new AuthChallengeInfo("bearer", "example", null, null, null);
        var error = new AuthenticationRequiredError(
                URI.create("https://agent.example.com"), challenge, null);

        assertEquals("AUTHENTICATION_REQUIRED", error.code());
        assertEquals("bearer", error.suggestedScheme());
        assertFalse(error.hasOAuth());
        assertNotNull(error.challenge());
    }

    @Test
    void authenticationRequiredError_with_oauth() {
        var oauth = new OAuthMetadataInfo(
                "https://auth.example.com/authorize",
                "https://auth.example.com/token",
                null, null);
        var error = new AuthenticationRequiredError(
                URI.create("https://agent.example.com"), null, oauth);

        assertTrue(error.hasOAuth());
        assertNull(error.suggestedScheme());
        assertEquals("https://auth.example.com/token",
                error.oauthMetadata().tokenEndpoint());
    }

    @Test
    void taskTimeoutError_carries_details() {
        var error = new TaskTimeoutError("task-123", 120000);

        assertEquals("TASK_TIMEOUT", error.code());
        assertEquals("task-123", error.taskId());
        assertEquals(120000, error.timeoutMs());
        assertTrue(error.getMessage().contains("120000"));
    }

    @Test
    void taskAbortedError() {
        var error = new TaskAbortedError("task-456", "client cancelled");

        assertEquals("TASK_ABORTED", error.code());
        assertEquals("task-456", error.taskId());
    }

    @Test
    void deferredTaskError_carries_token() {
        var error = new DeferredTaskError("defer-token-789");

        assertEquals("TASK_DEFERRED", error.code());
        assertEquals("defer-token-789", error.token());
    }

    @Test
    void validationError() {
        var error = new ValidationError("Invalid field value", "brief");

        assertEquals("VALIDATION_ERROR", error.code());
        assertEquals(java.util.List.of("brief"), error.path());
    }

    @Test
    void configurationError() {
        var error = new ConfigurationError("Missing agent URI", "agentUri");

        assertEquals("CONFIGURATION_ERROR", error.code());
        assertEquals("agentUri", error.configField());
    }

    @Test
    void versionUnsupportedError() {
        var error = new VersionUnsupportedError(
                "get_products", "version", "2.5",
                URI.create("https://agent.example.com"));

        assertEquals("VERSION_UNSUPPORTED", error.code());
        assertEquals("get_products", error.taskType());
        assertEquals("version", error.reason());
    }

    @Test
    void agentNotFoundError() {
        var error = new AgentNotFoundError("sales", List.of("marketing", "ops"));

        assertEquals("AGENT_NOT_FOUND", error.code());
        assertEquals("sales", error.agentId());
        assertEquals(List.of("marketing", "ops"), error.availableAgents());
    }

    @Test
    void unsupportedTaskError() {
        var error = new UnsupportedTaskError("get_products");

        assertEquals("UNSUPPORTED_TASK", error.code());
        assertEquals("get_products", error.taskName());
    }

    @Test
    void featureUnsupportedError() {
        var error = new FeatureUnsupportedError(
                List.of("webhooks"), List.of("products"));

        assertEquals("FEATURE_UNSUPPORTED", error.code());
        assertEquals(List.of("webhooks"), error.unsupportedFeatures());
    }

    @Test
    void responseTooLargeError() {
        var error = new ResponseTooLargeError(
                4096, 50000, URI.create("https://agent.example.com"));

        assertEquals("RESPONSE_TOO_LARGE", error.code());
        assertEquals(4096, error.limit());
        assertEquals(50000, error.bytesRead());
    }

    @Test
    void idempotencyErrors() {
        var conflict = new IdempotencyConflictError("key already in use");
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());

        var expired = new IdempotencyExpiredError("key TTL exceeded");
        assertEquals("IDEMPOTENCY_EXPIRED", expired.code());
    }

    @Test
    void all_errors_extend_adcpError() {
        // Verify the sealed hierarchy — all subclasses are AdcpError
        assertInstanceOf(AdcpError.class, new ProtocolError("mcp", "test", null));
        assertInstanceOf(AdcpError.class, new AuthenticationRequiredError(
                URI.create("https://a.com"), null, null));
        assertInstanceOf(AdcpError.class, new TaskTimeoutError(null, 1000));
        assertInstanceOf(AdcpError.class, new TaskAbortedError("t", null));
        assertInstanceOf(AdcpError.class, new DeferredTaskError("t"));
        assertInstanceOf(AdcpError.class, new ValidationError("m", null));
        assertInstanceOf(AdcpError.class, new ConfigurationError("m", null));
        assertInstanceOf(AdcpError.class, new VersionUnsupportedError(null, "r", null, null));
        assertInstanceOf(AdcpError.class, new AgentNotFoundError("a", List.of()));
        assertInstanceOf(AdcpError.class, new UnsupportedTaskError("t"));
        assertInstanceOf(AdcpError.class, new FeatureUnsupportedError(List.of(), List.of()));
        assertInstanceOf(AdcpError.class, new ResponseTooLargeError(1, 2, null));
        assertInstanceOf(AdcpError.class, new IdempotencyConflictError("m"));
        assertInstanceOf(AdcpError.class, new IdempotencyExpiredError("m"));
    }

    @Test
    void all_errors_are_unchecked() {
        // AdcpError extends RuntimeException — callers don't need try/catch
        assertInstanceOf(RuntimeException.class,
                new ProtocolError("mcp", "test", null));
    }
}
