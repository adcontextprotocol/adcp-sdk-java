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
    void basicCredentials_allows_blank_password() {
        // Blank passwords are valid — many platforms use username=token, password=""
        var creds = new BasicCredentials("user", "");
        assertEquals("", creds.password());
    }

    @Test
    void basicCredentials_rejects_colon_in_username() {
        assertThrows(IllegalArgumentException.class,
                () -> new BasicCredentials("us:er", "pass"));
    }

    @Test
    void basicCredentials_toString_redacts_password() {
        var creds = new BasicCredentials("user", "secret");
        String str = creds.toString();
        assertTrue(str.contains("user"));
        assertFalse(str.contains("secret"));
        assertTrue(str.contains("<REDACTED>"));
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
    void oauthClientCredentials_toString_redacts_secret() {
        var cc = new OAuthClientCredentials(
                "my-id", "super-secret", "https://auth.example.com/token", "read");
        String str = cc.toString();
        assertTrue(str.contains("my-id"));
        assertFalse(str.contains("super-secret"));
        assertTrue(str.contains("<REDACTED>"));
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

    @Test
    void oauthTokens_toString_redacts_tokens() {
        var tokens = OAuthTokens.bearer("access-secret", "refresh-secret",
                Instant.now().plusSeconds(300));
        String str = tokens.toString();
        assertFalse(str.contains("access-secret"));
        assertFalse(str.contains("refresh-secret"));
        assertTrue(str.contains("<REDACTED>"));
    }

    @Test
    void oauth_access_token_rejects_crlf() {
        assertThrows(IllegalArgumentException.class,
                () -> OAuthTokens.bearer("token\r\nX-Injected: bad"));
        assertThrows(IllegalArgumentException.class,
                () -> OAuthTokens.bearer("token\ninjection"));
    }
}
