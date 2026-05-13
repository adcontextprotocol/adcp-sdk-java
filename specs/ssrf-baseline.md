# SSRF baseline for outbound HTTP

**Status:** Design spec for the harness `AdcpHttpClient` skeleton
**Tracks:** [`transport`](../ROADMAP.md#track-3--l0-transport-mcp--a2a) (primary owner), [`infra`](../ROADMAP.md#track-1--build-repo-release-infra) (CI gate)
**Decisions referenced:** D2 (JDK 21), D9 (MCP SDK)

## Why this exists

The TS SDK shipped a cross-cutting SSRF migration on every outbound discovery probe across the 6.16 → 7.x line ([`detectProtocol`, `discoverAgentProfile`, `fetchAdAgentsJsonFromUrl`, `network-consistency-checker`, the property-crawler, the buyer-side discovery path](https://github.com/adcontextprotocol/adcp-client/releases)). The behavior is now baseline: every probe resolves DNS once, validates the full address set against address-guards, pins the connect to the first validated address, sets `redirect: 'manual'`, and caps the response body at 4 KiB.

The Java SDK has to match this baseline on every outbound discovery call — **not as a v1.x hardening pass, as v0.1 baseline**. JDK `HttpClient` doesn't pin connect-addresses natively. That's the design problem this spec addresses.

## Threat model — what we're defending against

| Class | Example |
|---|---|
| Cloud metadata exfiltration | `http://169.254.169.254/latest/meta-data/iam/security-credentials/` (AWS), `http://metadata.google.internal/computeMetadata/v1/` (GCP), `http://169.254.169.254/metadata/instance` (Azure) |
| RFC 1918 internal exploration | `http://10.0.0.1/admin` |
| Loopback service abuse | `http://localhost:9200/` Elasticsearch, `http://127.0.0.1:8500/v1/agent/services` Consul |
| DNS rebinding | Domain that resolves to a public IP at TXT-record time and to `127.0.0.1` at connect time |
| Redirect-to-internal | Public agent returns `Location: http://10.0.0.1/`; client follows |
| Slow-loris / huge-body | Public agent returns a 10 GB body to exhaust the prober's memory |

The first four classes target *what we connect to.* The fifth bypasses static URL validation by deferring the attack to redirect-follow time. The sixth weaponizes the prober's resource budget.

## The four mitigations

1. **Resolve DNS once, validate the full address set.** For an outbound URL whose host is a name, resolve to the full address list, run every address through the address-guard, accept the call **only if all addresses pass**. Defense-in-depth against partial-block lists (host with one public + one private A record).
2. **Pin the connect to the first validated address.** Bypass `HttpClient`'s implicit re-resolution. Defense against DNS rebinding: even if the name's records change between resolution and connect, we connect to the IP we already validated.
3. **`redirect: 'manual'`.** No transparent redirect-follow. A 3xx from a probe is a result, not an action. Callers that need a follow re-run validation against the new URL.
4. **Body cap.** 4 KiB by default on probes (the agent-card / `tools/list` size class). Configurable per call site.

## Address guards (block list)

The default `SsrfPolicy.allowsAddress(InetAddress)` denies:

| Range | RFC / notes |
|---|---|
| `0.0.0.0/8`, `::/128` | "This network" |
| `10.0.0.0/8` | RFC 1918 private |
| `100.64.0.0/10` | RFC 6598 carrier-grade NAT |
| `127.0.0.0/8`, `::1/128` | Loopback |
| `169.254.0.0/16`, `fe80::/10` | Link-local (includes cloud metadata 169.254.169.254) |
| `172.16.0.0/12` | RFC 1918 private |
| `192.0.0.0/24` | RFC 6890 IETF protocol assignments |
| `192.168.0.0/16` | RFC 1918 private |
| `198.18.0.0/15` | RFC 2544 benchmark |
| `224.0.0.0/4`, `ff00::/8` | Multicast |
| `240.0.0.0/4` | Reserved |
| IPv4-mapped IPv6 (`::ffff:0:0/96`) | Tunneling private IPv4 in IPv6 |
| `fc00::/7` | IPv6 unique local addresses |

For development against `http://localhost`, callers opt out per-request via `AdcpHttpClient.builder().ssrfPolicy(SsrfPolicy.permissive()).build()` — never via env-var or system property, so a misconfigured production deploy can't silently disable the guard.

## API shape

```java
// Spec-level: this is the contract, not the final API.
package org.adcontextprotocol.adcp.http;

public sealed interface SsrfPolicy {
    SsrfDecision evaluate(InetAddress address);
    boolean allowsHost(String host);

    static SsrfPolicy strict() { ... }     // baseline; denies the table above
    static SsrfPolicy permissive() { ... } // local dev only; documented opt-in
}

public sealed interface SsrfDecision {
    record Allow() implements SsrfDecision {}
    record Deny(String reason) implements SsrfDecision {}
}

public final class AdcpHttpClient {
    public static Builder builder();
    public HttpResponse<String> send(HttpRequest req, BodySpec body);
    // ...
}
```

`SsrfPolicy.strict()` is the default; every call site that constructs an `AdcpHttpClient` without specifying a policy gets it.

## Test contract

The skeleton ships a JUnit 5 test suite covering:

- `strict()` denies a literal IP from each entry in the block table
- `strict()` denies a hostname whose A/AAAA records resolve to a denied address
- `strict()` allows a public-looking A record (uses fixture rather than live DNS)
- Redirect to a denied address is rejected (rule 3)
- Body larger than the configured cap is truncated and the truncation is signaled (rule 4)
- The DNS-pin path connects to the resolved address even when the OS resolver returns a different address on a second lookup (rule 2 — DNS-rebinding test)

The DNS-rebinding test is the hardest; the harness skeleton stubs it out behind `@Disabled("requires DNS-rebinding test harness — see [transport] track")`. Real implementation lands on the [`transport`](../ROADMAP.md#track-3--l0-transport-mcp--a2a) track.

## What lands in the harness vs. on the `transport` track

| Harness (this spec) | `transport` track |
|---|---|
| `SsrfPolicy` interface + strict/permissive defaults | Per-call policy override on the HTTP client |
| Address-guard implementation | DNS-pin connect-address binding |
| Body-cap helper | Streaming body cap (current spec is in-memory only) |
| `AdcpHttpClient.builder()` skeleton + a single working `send()` path with redirect:manual | All discovery-probe call sites switched onto `AdcpHttpClient` |
| Tests for address-guard, body cap, redirect | DNS-rebinding integration test |
| Documented opt-out for `localhost` dev | Conformance harness assertion that production builds default to `strict()` |

## References

- [RFC 6890 — Special-Purpose IP Address Registries](https://www.rfc-editor.org/rfc/rfc6890)
- [TS SDK PR #1633 — cross-cutting SSRF migration for discovery layer](https://github.com/adcontextprotocol/adcp-client/pull/1633)
- TS SDK release notes 6.16–7.x — see `ROADMAP.md` §7.x deltas
