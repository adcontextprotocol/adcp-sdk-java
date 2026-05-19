package org.adcontextprotocol.adcp.auth;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

/**
 * OAuth 2.0 tokens obtained from an auth-code or refresh grant.
 *
 * @param accessToken  the access token
 * @param refreshToken the refresh token (may be {@code null} for CC grants)
 * @param expiresAt    when the access token expires (may be {@code null})
 * @param tokenType    token type (typically "Bearer")
 */
public record OAuthTokens(
        String accessToken,
        @Nullable String refreshToken,
        @Nullable Instant expiresAt,
        String tokenType
) {

    public OAuthTokens {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(tokenType, "tokenType");
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be blank");
        }
    }

    /** Creates a Bearer token with the given access token. */
    public static OAuthTokens bearer(String accessToken) {
        return new OAuthTokens(accessToken, null, null, "Bearer");
    }

    /** Creates a Bearer token with refresh token. */
    public static OAuthTokens bearer(String accessToken, @Nullable String refreshToken,
                                     @Nullable Instant expiresAt) {
        return new OAuthTokens(accessToken, refreshToken, expiresAt, "Bearer");
    }

    /** Whether the access token has expired (with 30s safety margin). */
    public boolean isExpired() {
        if (expiresAt == null) {
            return false;
        }
        return Instant.now().plusSeconds(30).isAfter(expiresAt);
    }

    @Override
    public String toString() {
        return "OAuthTokens[accessToken=<REDACTED>, refreshToken="
                + (refreshToken != null ? "<REDACTED>" : "null")
                + ", expiresAt=" + expiresAt
                + ", tokenType=" + tokenType + "]";
    }
}
