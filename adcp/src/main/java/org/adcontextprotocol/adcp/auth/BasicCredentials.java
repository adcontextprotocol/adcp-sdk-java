package org.adcontextprotocol.adcp.auth;

import java.util.Objects;

/**
 * HTTP Basic credentials (RFC 7617).
 *
 * <p>Validated at construction: neither {@code username} nor
 * {@code password} may be blank.
 *
 * @param username the username
 * @param password the password
 */
public record BasicCredentials(String username, String password) {

    public BasicCredentials {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
    }
}
