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

public interface SigningContext {
    AdcpUse use();
    @Nullable TenantId tenant();
    @Nullable PrincipalRef principal();

    static Builder builder(AdcpUse use) { ... }
}
```

`tenant` is the operator-side tenant identity used for key selection. `principal` is the on-behalf-of account/principal identity, such as an advertiser or buyer principal represented by an agency/DSP caller. They may be equal in simple deployments, but the API keeps them separate.

The public signing SPI takes `SigningContext` on the outbound signing path:

```java
public interface SigningProvider {
    Signature sign(SigningContext context, SigningInput input);
}
```

Inbound verification starts from the signed request, especially the `kid` header. The resolver maps the inbound key id to a verification key and any resolved tenant/principal metadata; the verifier then checks the signature and `adcp_use` purpose.

```java
public interface VerificationKeyResolver {
    VerificationKeyLookup resolve(VerificationInput input);
}

public record VerificationInput(
        AdcpUse expectedUse,
        String kid,
        SignedInput input) {
}

public sealed interface VerificationKeyLookup {
    record Found(VerificationKey key, @Nullable TenantId tenant, @Nullable PrincipalRef principal)
            implements VerificationKeyLookup {}
    record Missing(String kid) implements VerificationKeyLookup {}
}
```

There is no shipped API shaped as `SigningProvider.forUse(AdcpUse)`. v0.2 ships the `SigningContext`-based surface only.

## Selection rules

- `use` is required and maps to the JWK `adcp_use` purpose check.
- `tenant` is nullable for single-tenant deployments and caller-side signing where no publisher account has been resolved yet.
- `principal` is nullable and carries the resolved account/principal when available.
- Providers may use `tenant`, `principal`, or both to select `kid`; they must not ignore `use`.
- `kid` is an opaque lookup key into a pre-provisioned key set. Resolver implementations must not parse authority, tenant, or URL semantics out of `kid`.
- Resolver implementations must not dereference attacker-controlled URLs from an inbound signed object. If a resolver refreshes a JWKS or other key source over HTTP, that fetch uses the strict SSRF-safe HTTP client from [`ssrf-baseline.md`](ssrf-baseline.md).
- Verification starts from inbound `kid`; tenant context is derived after key lookup and never assumed before signature verification.
- Verification still enforces the key purpose at JWK `adcp_use`.
- If verification returns `Found(key, null, null)`, receivers treat the request as untenanted and reject tenant-scoped operations. They must not recover tenant identity from unsigned body, header, or query fields after the fact.

## Release notes

The v0.2 release notes must warn `SigningContext.tenant()` may be null until v0.3 wires tenant resolution from `AccountStore` / `adagents.json`. Provider implementations must not bake in a single-tenant assumption just because early alpha contexts are null.

## Milestone contract

v0.2 freezes the `SigningContext` parameter set and the `SigningProvider` / `VerificationKeyResolver` SPI direction. v0.3 wires `AccountStore` / `adagents.json` principal resolution into the context passed to webhook and signed-request providers.
