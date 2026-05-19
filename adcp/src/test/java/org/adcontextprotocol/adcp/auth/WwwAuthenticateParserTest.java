package org.adcontextprotocol.adcp.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WwwAuthenticateParser}.
 */
class WwwAuthenticateParserTest {

    @Test
    void parses_bearer_with_realm() {
        AuthChallengeInfo info = WwwAuthenticateParser.parse(
                "Bearer realm=\"example\"");

        assertNotNull(info);
        assertEquals("bearer", info.scheme());
        assertEquals("example", info.realm());
        assertNull(info.scope());
        assertNull(info.error());
    }

    @Test
    void parses_bearer_with_error() {
        AuthChallengeInfo info = WwwAuthenticateParser.parse(
                "Bearer realm=\"api\", error=\"invalid_token\", "
                        + "error_description=\"Token expired\"");

        assertNotNull(info);
        assertEquals("bearer", info.scheme());
        assertEquals("api", info.realm());
        assertEquals("invalid_token", info.error());
        assertEquals("Token expired", info.errorDescription());
    }

    @Test
    void parses_bearer_with_scope() {
        AuthChallengeInfo info = WwwAuthenticateParser.parse(
                "Bearer scope=\"read write\"");

        assertNotNull(info);
        assertEquals("bearer", info.scheme());
        assertEquals("read write", info.scope());
    }

    @Test
    void parses_basic_with_realm() {
        AuthChallengeInfo info = WwwAuthenticateParser.parse(
                "Basic realm=\"Agent Admin\"");

        assertNotNull(info);
        assertEquals("basic", info.scheme());
        assertEquals("Agent Admin", info.realm());
    }

    @Test
    void parses_scheme_only() {
        AuthChallengeInfo info = WwwAuthenticateParser.parse("Bearer");

        assertNotNull(info);
        assertEquals("bearer", info.scheme());
        assertNull(info.realm());
    }

    @Test
    void scheme_is_lowercased() {
        AuthChallengeInfo info = WwwAuthenticateParser.parse("BEARER realm=\"x\"");

        assertNotNull(info);
        assertEquals("bearer", info.scheme());
    }

    @Test
    void returns_null_for_blank() {
        assertNull(WwwAuthenticateParser.parse(null));
        assertNull(WwwAuthenticateParser.parse(""));
        assertNull(WwwAuthenticateParser.parse("   "));
    }

    @Test
    void parses_unquoted_values() {
        AuthChallengeInfo info = WwwAuthenticateParser.parse(
                "Bearer realm=example, error=invalid_token");

        assertNotNull(info);
        assertEquals("example", info.realm());
        assertEquals("invalid_token", info.error());
    }

    @Test
    void authChallengeInfo_lowercases_scheme() {
        AuthChallengeInfo info = new AuthChallengeInfo("Bearer", null, null, null, null);
        assertEquals("bearer", info.scheme(),
                "Scheme should be lowercased in the constructor");
    }
}
