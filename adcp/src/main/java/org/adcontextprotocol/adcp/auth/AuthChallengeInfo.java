package org.adcontextprotocol.adcp.auth;

import org.jspecify.annotations.Nullable;

/**
 * Parsed {@code WWW-Authenticate} challenge from an HTTP 401 response.
 *
 * <p>Fields follow RFC 9110 §11.6.1. The {@link #scheme()} is always
 * lowercased for case-insensitive comparison.
 *
 * @param scheme           auth scheme, lowercased (e.g. "bearer", "basic")
 * @param realm            the protection realm, if present
 * @param scope            OAuth scope, if present
 * @param error            OAuth error code, if present
 * @param errorDescription human-readable error description, if present
 */
public record AuthChallengeInfo(
        String scheme,
        @Nullable String realm,
        @Nullable String scope,
        @Nullable String error,
        @Nullable String errorDescription
) {
    public AuthChallengeInfo {
        java.util.Objects.requireNonNull(scheme, "scheme");
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        if (scheme.isBlank()) {
            throw new IllegalArgumentException("scheme must not be blank");
        }
    }
}
