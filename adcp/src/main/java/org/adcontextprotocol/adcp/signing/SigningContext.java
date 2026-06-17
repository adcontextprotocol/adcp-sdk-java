package org.adcontextprotocol.adcp.signing;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Carries the purpose and tenant/principal context for signing key selection.
 *
 * <p>Per D22: {@code use} is always required. {@code tenant} and
 * {@code principal} are nullable for single-tenant deployments and caller-side
 * signing where no publisher account has been resolved yet.
 *
 * <p>There is no {@code forUse(AdcpUse)} shortcut — the context-based surface
 * is the only public API.
 *
 * @param use       the signing purpose (required)
 * @param tenant    operator-side tenant identity, or {@code null} for single-tenant
 * @param principal on-behalf-of principal, or {@code null} when not resolved
 */
public record SigningContext(AdcpUse use, @Nullable TenantId tenant, @Nullable PrincipalRef principal) {

    public SigningContext {
        Objects.requireNonNull(use, "use");
    }

    /** Start building a {@code SigningContext} with the required purpose. */
    public static Builder builder(AdcpUse use) {
        return new Builder(use);
    }

    /** Builder for {@link SigningContext}. */
    public static final class Builder {
        private final AdcpUse use;
        private @Nullable TenantId tenant;
        private @Nullable PrincipalRef principal;

        private Builder(AdcpUse use) {
            this.use = Objects.requireNonNull(use, "use");
        }

        public Builder tenant(@Nullable TenantId tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder principal(@Nullable PrincipalRef principal) {
            this.principal = principal;
            return this;
        }

        public SigningContext build() {
            return new SigningContext(use, tenant, principal);
        }
    }
}