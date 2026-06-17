package org.adcontextprotocol.adcp.signing;

import java.util.Objects;

/**
 * Operator-side tenant identity used for signing key selection.
 *
 * @param value the tenant identifier (must not be null or blank)
 */
public record TenantId(String value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("TenantId must not be blank");
        }
    }

    /** Factory method consistent with the codebase convention. */
    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}