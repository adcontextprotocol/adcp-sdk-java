# Signing context for tenant-aware key selection

**Status:** Design spec for D22
**Tracks:** [`signing`](../ROADMAP.md#track-4--l1-signing), [`multitenant`](../ROADMAP.md#track-5--l2-account-store-registry-multi-tenant)
**Decisions referenced:** D2, D22

## Why this exists

The Java signing surface cannot assume one signing key per `adcp_use`. Multi-tenant operators need to select a key by purpose plus resolved tenant/principal context, so a single JWKS endpoint can expose multiple keys with the same purpose and distinct `kid` values.

D22 freezes the direction before the `signing` track opens: key selection is explicit-parameter based. Tenant context is passed as data, not hidden in `ScopedValue`, so callers, test fixtures, and framework adapters can see which tenant drives `kid` selection. `ScopedValue` remains available for request-scoped recorder/account context elsewhere, but not as the primary signing API.

## API shape

```java
package org.adcontextprotocol.adcp.signing;

import org.jspecify.annotations.Nullable;

public record SigningContext(
        AdcpUse use,
        @Nullable TenantId tenant,
        @Nullable PrincipalRef principal) {
}
```

The public signing SPI takes `SigningContext`:

```java
public interface SigningProvider {
    Signature sign(SigningContext context, SigningInput input);
    VerificationResult verify(SigningContext context, SignedInput input);
}
```

There is no release API shaped as `SigningProvider.forUse(AdcpUse)`. If a prototype includes that helper, it is removed before v0.2.

## Selection rules

- `use` is required and maps to the JWK `adcp_use` purpose check.
- `tenant` is nullable for single-tenant deployments and caller-side signing where no publisher account has been resolved yet.
- `principal` is nullable and carries the resolved account/principal when available.
- Providers may use `tenant`, `principal`, or both to select `kid`; they must not ignore `use`.
- Verification still enforces the key purpose at JWK `adcp_use`; tenant context only narrows which key is expected or acceptable.

## Milestone contract

v0.2 freezes the `SigningContext` type and SPI method signatures. v0.3 wires `AccountStore` / `adagents.json` principal resolution into the context passed to webhook and signed-request providers.
