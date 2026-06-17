package org.adcontextprotocol.adcp.signing;

import java.util.Objects;

/**
 * On-behalf-of account or principal identity for signing key selection.
 *
 * <p>In multi-tenant deployments this may differ from {@link TenantId}: a DSP
 * or agency tenant signs on behalf of an advertiser principal.
 *
 * @param value the principal identifier (must not be null or blank)
 */
public record PrincipalRef(String value) {

    public PrincipalRef {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("PrincipalRef must not be blank");
        }
    }

    /** Factory method consistent with the codebase convention. */
    public static PrincipalRef of(String value) {
        return new PrincipalRef(value);
    }

    @Override
    public String toString() {
        return value;
    }
}