package org.adcontextprotocol.adcp.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for auth credential records.
 */
class CredentialsTest {

    @Test
    void basicCredentials_validates() {
        var creds = new BasicCredentials("user", "pass");
        assertEquals("user", creds.username());
        assertEquals("pass", creds.password());
    }

    @Test
    void basicCredentials_rejects_blank_username() {
        assertThrows(IllegalArgumentException.class,
                () -> new BasicCredentials("", "pass"));
        assertThrows(IllegalArgumentException.class,
                () -> new BasicCredentials("  ", "pass"));
    }

    @Test
    void basicCredentials_rejects_blank_password() {
        assertThrows(IllegalArgumentException.class,
                () -> new BasicCredentials("user", ""));
    }

    @Test
    void basicCredentials_rejects_null() {
        assertThrows(NullPointerException.class,
                () -> new BasicCredentials(null, "pass"));
        assertThrows(NullPointerException.class,
                () -> new BasicCredentials("user", null));
    }

    @Test
    void oauthClientCredentials_validates() {
        var cc = new OAuthClientCredentials(
                "id", "secret", "https://auth.example.com/token", "read");
        assertEquals("id", cc.clientId());
        assertEquals("read", cc.scope());
    }

    @Test
    void oauthClientCredentials_rejects_blank() {
        assertThrows(IllegalArgumentException.class,
                () -> new OAuthClientCredentials("", "s", "t", null));
        assertThrows(IllegalArgumentException.class,
                () -> new OAuthClientCredentials("id", "", "t", null));
    }

    @Test
    void oauthTokens_bearer_factory() {
        var tokens = OAuthTokens.bearer("access-123");
        assertEquals("access-123", tokens.accessToken());
        assertEquals("Bearer", tokens.tokenType());
        assertNull(tokens.refreshToken());
        assertFalse(tokens.isExpired());
    }

    @Test
    void oauthTokens_expired() {
        var tokens = OAuthTokens.bearer(
                "access", "refresh", Instant.now().minusSeconds(60));
        assertTrue(tokens.isExpired());
    }

    @Test
    void oauthTokens_not_expired() {
        var tokens = OAuthTokens.bearer(
                "access", "refresh", Instant.now().plusSeconds(300));
        assertFalse(tokens.isExpired());
    }

    @Test
    void oauthTokens_rejects_blank_access_token() {
        assertThrows(IllegalArgumentException.class,
                () -> OAuthTokens.bearer(""));
    }
}
