# Java SDK Implementation Plan

Working plan to take the [Java SDK RFC](docs/rfc/java-sdk-rfc.md) from zero to v1.0 GA at full L0–L3 parity with `@adcp/sdk` (TS) and `adcp` (Python).

This is a living document. Status updates land here; design decisions land in the RFC or in a follow-up RFC.

## Confirmed decisions

Decisions made post-RFC that supersede or refine the merged text. The table below is the index. When a row needs more space to explain, it gets its own `specs/<topic>.md` (per D16) — by default the table row is the spec.

| # | Decision | Resolution | Supersedes RFC |
|---|---|---|---|
| D1 | Repo home | Work in `adcontextprotocol/adcp-sdk-java` on feature branches → PR to `main`. | RFC §Build, distribution, governance (named the repo, didn't specify cadence) |
| D2 | Java baseline | **JDK 21 only.** Drops the 17 floor. Consequences: no `*Async` mirror surface (virtual threads make sync API scale natively), no platform-thread fallback executor in `WebhookEmitter`, `ScopedValue` directly for `UpstreamRecorder` per-principal scope, no Spring Boot 2.7 long-tail support. | RFC §Architecture / Java baseline (was "Java 17 LTS"); RFC §Async model (drops the 12-method `*Async` mirror) |
| D3 | Maven coordinates | Group `org.adcontextprotocol`. Artifacts: `adcp` (main), `adcp-server`, `adcp-testing`, `adcp-spring-boot-starter`, `adcp-cli`, `adcp-reactor`, `adcp-mutiny`, `adcp-kotlin`. Base Java package `org.adcontextprotocol.adcp.*`. Sub-packages by surface (e.g. `.task`, `.server`, `.signing`, `.testing`). | RFC §Reference (named artifacts, didn't pin group / base package) |
| D4 | Protocol tarball Sigstore | Confirmed signed. Harness schema-fetcher runs `cosign verify-blob` per RFC, no checksum-only fallback path. | Confirms RFC §Schema-bundle consumption assumption |
| D5 | Reference mock-server | The `@adcp/sdk/mock-server` package — same mock-server TS uses. See D8 for CI deployment shape. | Specifies RFC §`comply_test_controller` "shared reference mock-server" |
| D6 | Maven Central publish cadence | **Hold first publish until v0.3 alpha.** v0.1 and v0.2 ship as local Gradle artifacts / SNAPSHOT only. Sonatype OSSRH namespace claim + GPG key setup still happens harness Week 1 (slow path; 1–5 business-day ticket) — we just don't push artifacts until v0.3. | RFC §Build, distribution, governance (RFC said "Maven Central alpha from v0.1") |
| D7 | `javax`/`jakarta` floor | **`jakarta` only**, Spring Boot 3.x floor. Single `adcp-spring-boot-starter` artifact, no compat starter, no community 2.7 port. Spring Boot 2.7 OSS support ended Nov 2025; anyone still on it has a vendor relationship. | Resolves RFC Open Question 6 in favor of option (a) |
| D8 | Mock-server CI deployment | **Sidecar via `npx adcp mock-server`.** GitHub Actions Node step installs a pinned `@adcp/sdk` version, backgrounds one mock-server per specialism on a port range, Java tests hit `localhost`. The pinned `@adcp/sdk` version is the CI conformance oracle for executable behavior, but never overrides D23's signed protocol bundle for schema/API truth. If they diverge, the release-blocking fix is to bump/patch the CI mock-server pin; a temporary exception requires WG maintainer approval in the same PR, an issue link, and an explicit statement that storyboard CI still passes or which compatibility assertion is intentionally skipped. Promote to a published Docker image if multi-specialism orchestration becomes unwieldy. | Specifies D5's deployment |
| D9 | MCP Java SDK | **`io.modelcontextprotocol.sdk:mcp-core:1.1.2` + `mcp-json-jackson2:1.1.2`** at the core (not the `mcp` bundle artifact, which pulls jackson3). Used by `adcp` (caller) and `adcp-server` (agent). The Spring AI MCP SDK was donated to the `modelcontextprotocol` org in Feb 2025 and rebranded as the official Java SDK; current `spring-ai-mcp-*` artifacts are now thin Spring Boot wrappers on top of it — no parallel implementation. **License: MIT** (compatible, flagged for foundation position). Both prototype questions closed in [`specs/mcp-prototype-findings.md`](specs/mcp-prototype-findings.md): (a) `HttpServletStreamableServerTransportProvider` in `mcp-core` is framework-neutral — no Jetty/Tomcat dep at compile time, adopter brings their own servlet container at runtime; (b) `mcp-json-jackson2` and `mcp-json-jackson3` are at identical 1.1.2 cadence with the same surface — we pin to jackson2 to match the rest of the SDK's Jackson tree. | Resolves RFC Open Question 2 |
| D10 | A2A pre-1.0 type strategy | **Keep A2A types in-tree until `a2aproject/a2a-java` cuts a stable ≥ 1.0.0 release**, then migrate to the upstream client in one shot and deprecate the in-tree fallback in the next minor. As of the latest check, `a2a-java` is at `1.0.0.Beta1` (Apr 2026) — package layout still churning, so we don't hard-depend on it yet. | RFC default for Open Question 3 |
| D11 | `TransitionGuard` narrowing protection | **Guards declare which spec edges they touch.** Conformance harness fails if a sandbox account's guards narrow any edge the storyboards exercise. Guards run after the spec edge check and can never relax a spec edge. | Resolves RFC Open Question 7 |
| D12 | Spring Security integration depth | **Recipes-only at v1.0.** No separate `adcp-spring-boot-starter-security` artifact. Auth models vary too much to pre-bake; recipes age better than autoconfig. Revisit if v0.3 design-partner feedback demands it. | RFC default for Open Question 5 |
| D13 | Reactor + Mutiny adapters | **At GA, not fast-follow.** `adcp-reactor` and `adcp-mutiny` both ship in v1.0. WebFlux shops left to wrap the sync API would own that complexity forever and we'd lose the canonical surface. | Confirms RFC §Async model |
| D14 | Kotlin co-release | **At v1.0, thin extension artifact** (`adcp-kotlin`). Coroutine `suspend fun` wrappers + DSL builders. Not a parallel SDK. Defer and Kotlin shops fork. | Confirms RFC §Kotlin positioning |
| D15 | Spec-rev tracking cadence | **≤ 2 weeks from AdCP spec rev to a Java SDK release that consumes it.** Same SLO TS and Python hold to. Slower than 2 weeks and JVM teams stop trusting the parity claim. The release must track the signed bundle pin for build/cache inputs and emit the release-precision `adcp_version` wire token after normalization. Before D6's first Maven Central publish, this SLO is satisfied by a tagged local/SNAPSHOT alpha; if 3.1 GA cuts between v0.2 and v0.3, cut an off-cycle v0.2.x local/SNAPSHOT rather than waiting for v0.3, unless the WG explicitly records a D15 exception. | Specifies RFC §What kills adoption (item 1); refined by D23 |
| D16 | Design-decision filing convention | **Match AdCP convention.** Longer-form design / decision docs live in `specs/<topic>.md` (the same place the Java SDK RFC itself lives in the spec repo). The Confirmed-decisions table in this ROADMAP is the at-a-glance index; a decision spins up its own `specs/` doc only when the table row isn't enough. No `docs/adr/`, no MADR template, no per-decision file by default. | (No RFC §; sets a repo convention) |
| D17 | Branching model | **Trunk-based.** Short-lived feature branches → PR to `main`. Semver tags from `main`. No long-lived release branches. | (Sets a repo convention; matches TS SDK) |
| D18 | Commits + changelog | **Conventional Commits enforced via commitlint; [Changesets](https://github.com/changesets/changesets) for the CHANGELOG.** Matches the TS SDK's workflow shape (same `.changeset/` directory pattern) so humans and tools read both SDKs' release notes the same way. | (Sets a repo convention; matches TS SDK) |
| D19 | Contributor IPR | **Replicate the AAO IPR Bot pattern** used by adcp / adcp-client / adcp-client-python / adcp-go. Contributors agree by commenting `I have read the IPR Policy` on their first PR; the AAO IPR Bot (a GitHub App) enforces via a required status check. Harness Week 1 actions: (1) foundation admin adds `adcontextprotocol/adcp-sdk-java` to the App's installation scope and the `IPR_APP_ID` / `IPR_APP_PRIVATE_KEY` org-secret scope; (2) add the ~15-line caller workflow at `.github/workflows/ipr.yml` invoking `adcontextprotocol/adcp/.github/workflows/ipr-check-callable.yml@main`; (3) `CONTRIBUTING.md` mirrors the adcp wording and links to `IPR_POLICY.md` in the spec repo. **No DCO. No CLA.** | (Matches AAO standard) |
| D20 | Sonatype OSSRH namespace claim | **Claim `org.adcontextprotocol` via DNS TXT verification.** Maven Central confirms the namespace is unclaimed (zero artifacts today). Requires the foundation to add one `TXT` record to `adcontextprotocol.org` proving control. Sonatype ticket + DNS record both kicked off harness Week 1; first publish waits for v0.3 (per D6). | Specifies D6 |
| D21 | Branch protection on `main` | **Required: 1 code-owner approving review, required CI checks (`build`, `test`, `storyboard`, `IPR Policy / Signature`), no force-push, no direct push, no admin bypass.** Dependabot patch PRs auto-merge after green CI. Two-reviewer gate doesn't add safety with a founder pair; revisit when contributor base grows. | (Sets a repo convention) |
| D22 | Multi-tenant signing keys | **Signing key selection is tenant-aware, not just `adcp_use`-aware.** The L1 signing SPI must select by purpose plus caller/resolved publisher tenant context, a JOSE-compatible pattern where one operator can publish multiple tenant keys on a single JWKS endpoint: same `adcp_use`, distinct `kid` per tenant. v0.2 freezes an explicit-parameter `SigningContext` shape — `AdcpUse use()`, nullable tenant identity, nullable principal reference — and retires any single-arg `SigningProvider.forUse(AdcpUse)` API before release. v0.3 wires tenant resolution from `AccountStore` / `adagents.json` into that selector. Longer-form shape lives in [`specs/signing-context.md`](specs/signing-context.md). | Refines RFC §L1 signing and §L2 multi-tenant principal resolution |
| D23 | AdCP 3.1 beta parity target | **Track the signed protocol bundle as the source of truth; use TS/Python as implementation references.** For this roadmap update the pinned target is `3.1.0-beta.5` (2026-05-26). If 3.1 GA cuts before the v0.1 codegen PR, bump to GA deliberately in that PR; otherwise beta.5 is the target. `@adcp/sdk@8.1.0-beta.13` is aligned to beta.5; Python `6.3.0b4` is on beta.4 and is a reference, not a blocker. Java's local `ADCP_VERSION` remains `3.0.11`; v0.1 must include wire-version negotiation, multi-bundle schema loading, and 3.1 beta/GA codegen coverage before claiming cross-SDK parity. | Updates RFC parity baseline and D15 spec-rev SLO |

## Parity baseline (as of 2026-05-26)

The RFC tracks `@adcp/sdk` 6.x and the first Java scaffold still pins `ADCP_VERSION=3.0.11`. Current upstream parity moved again. Precedence for Java work is: signed protocol bundle first, protocol release notes/spec docs second, pinned mock-server behavior for CI conformance third, TS/Python implementation behavior fourth. A mock-server mismatch blocks release by forcing a mock-server pin/patch decision; it does not redefine the protocol.

| Source | Version checked | Java parity read |
|---|---|---|
| AdCP protocol bundle | `3.1.0-beta.5` | Java is behind on schema pin and 3.1 compliance bundle coverage. The next codegen bump should target beta.5 unless 3.1 GA has cut by then. |
| `@adcp/sdk` (TS) | `8.1.0-beta.13`, `adcp_version=3.1.0-beta.5` | Best reference for 3.1 caller/server ergonomics, conformance runner behavior, canonical-format helpers, cache-scope guards, and beta migration shims. Aligned to the target bundle. |
| `adcp` (Python) | `6.3.0b4`, packaged `ADCP_VERSION=3.1.0-beta.4` | Best reference for Pydantic/server semantics, request-scoped capabilities, externally-managed webhook signing capabilities, resolver hardening, and version-routed validation. One beta behind the target bundle, so use for API shape, not as the normative schema source. |
| `adcp-go` | v1.x (dev) | Still useful as a third-language sanity check, especially for typed version pins and signer/verifier shapes. |

**Net: Java is not fully up to date.** The roadmap now treats 3.1 beta parity as a v0.1 input, not a post-v0.1 cleanup. The remaining gap is not just generated types: Java needs version negotiation, multi-bundle validators, cache-scope semantics, 3.1 compliance bundle execution, and a handful of SDK helper surfaces that TS/Python have already exposed.

### 7.x / 8.x deltas since the RFC was written

Read from the TS and Python SDK changelogs plus the AdCP 3.1 beta release notes. Each delta lists the Java track it folds into. None invalidate the RFC; all bump scope inside existing tracks.

**AdCP 3.1 protocol surfaces**

- **Wire-version negotiation** (`adcp_version`, `supported_versions`, response echo, `VERSION_UNSUPPORTED`). Keep two tiers distinct: bundle pin (`3.1.0-beta.5`, full semver, build/cache key) and release-precision wire token (`3.1` for stable releases, `3.1-beta.N` while the beta line is live after normalizing full prerelease pins such as `3.1.0-beta.N`). Current Java code only models the stable `major.minor` form, so v0.1 must expand `AdcpVersion` to expose both `.bundle()` and `.wire()` before 3.1 beta traffic is supported. Java must emit both `adcp_version` and legacy `adcp_major_version` through 3.x, pick response validators by served version, route unversioned legacy traffic to `3.0`, and collapse prerelease handling back to stable `3.1` when 3.1 GA cuts. → [`transport`](#track-3--l0-transport-mcp--a2a) + [`codegen`](#track-2--l0-types--codegen) + [`testing`](#track-9--testing--conformance).
- **Multi-bundle schema loading.** TS ships 3.0 and 3.1-beta caches and can run external 3.0 compliance bundles from a 3.1-beta runner. Python ships 2.5, 3.0, and 3.1 beta caches. Java needs the same loader shape, not a single `ADCP_VERSION` global. → [`codegen`](#track-2--l0-types--codegen) + [`testing`](#track-9--testing--conformance).
- **Protocol envelope status is required.** Every task response, including sync reads like `get_adcp_capabilities`, carries top-level envelope `status`. Server helpers should stamp it centrally; domain payload aliases should not force adopter handlers to return envelope fields by hand. → [`transport`](#track-3--l0-transport-mcp--a2a) + [`codegen`](#track-2--l0-types--codegen).
- **Media-buy lifecycle status split.** `create_media_buy` and `update_media_buy` success payloads use `media_buy_status`; legacy top-level body `status` is deprecated because `status` now belongs to the task envelope. Java must read canonical first, tolerate legacy during the 3.1 window, and avoid confusing A2A task artifact status with domain status. → [`codegen`](#track-2--l0-types--codegen) + [`transport`](#track-3--l0-transport-mcp--a2a) + [`testing`](#track-9--testing--conformance).
- **Governance status renames.** Experimental governance responses moved root body status to `verdict` / `outcome_state`, and audit entries use `entries[].verdict`. → [`codegen`](#track-2--l0-types--codegen).
- **Open error-code decoding.** As SDK forward-compatibility practice, receivers should not fail closed on unknown `error.code`; classify from `recovery` and default conservatively when absent. Java generated enums need an unknown-value strategy, not a hard enum parse failure. → [`codegen`](#track-2--l0-types--codegen) + [`transport`](#track-3--l0-transport-mcp--a2a).
- **Advisory `errors[]` on success payloads.** `STALE_RESPONSE`, canonical-format projection warnings, pixel-tracker downgrade/upgrade warnings, and similar advisories ride in payload `errors[]` while transport/task success remains success. Java callers and validators must not promote advisory payload errors to thrown failures. → [`transport`](#track-3--l0-transport-mcp--a2a) + [`testing`](#track-9--testing--conformance).
- **Universal request `idempotency_key`.** 3.1 read tools accept every-request envelope fields and the compliance suite probes this. Java request builders and MCP wrapper validation must tolerate and emit envelope fields on every task request, not only write calls. → [`codegen`](#track-2--l0-types--codegen) + [`transport`](#track-3--l0-transport-mcp--a2a) + [`async-l3`](#track-6--l3-idempotency-async-tasks-webhooks).
- **Webhook token round-trip and endpoint proof-of-control.** `McpWebhookPayload.token` is typed; durable account-level webhook configs require proof-of-control semantics and stable `subscriber_id` replace/upsert behavior. → [`signing`](#track-4--l1-signing) + [`async-l3`](#track-6--l3-idempotency-async-tasks-webhooks) + [`multitenant`](#track-5--l2-account-store-registry-multi-tenant).

**3.1 buying, catalog, and signal surface**

- **Wholesale feed mirroring.** `get_products` / `get_signals` support `wholesale_feed_version`, `if_wholesale_feed_version`, optional pricing version, `unchanged`, and required `cache_scope` (`public` / `account`). Account-level wholesale feed webhooks deliver `product.*`, `signal.*`, and `wholesale_feed.bulk_change` with repair through read tools. → [`multitenant`](#track-5--l2-account-store-registry-multi-tenant) + [`async-l3`](#track-6--l3-idempotency-async-tasks-webhooks) + [`testing`](#track-9--testing--conformance).
- **Cache-scope guardrails.** TS now fails closed when server payloads omit `cache_scope` on product responses. Java server builders should make this hard to omit on account-scoped product/signal responses, because cache-scope is the safety property that prevents leaking account overlays through shared caches. → [`transport`](#track-3--l0-transport-mcp--a2a) + [`multitenant`](#track-5--l2-account-store-registry-multi-tenant).
- **Wholesale signals and `SignalRef`.** `get_signals discovery_mode=wholesale`, canonical `signal_ref` identities, product-local / data-provider / signal-source scopes, selectable `signal_targeting_options`, `signal_targeting_rules`, and package-level `targeting_overlay.signal_targeting_groups`. Legacy `signal_id` remains compatibility input. → [`codegen`](#track-2--l0-types--codegen) + [`multitenant`](#track-5--l2-account-store-registry-multi-tenant).
- **Canonical creative formats.** `format_options[]`, `format_option_refs`, `format_option_id`, `v1_format_ref`, named canonical format helpers, v1↔v2 projection, pixel-tracker advisory downgrades, and cache-backed canonical registries. The beta.2 `capability_ids` write path was removed before beta.5; Java should model the beta.5 `format_option_*` names from the start. → [`codegen`](#track-2--l0-types--codegen) + [`transport`](#track-3--l0-transport-mcp--a2a).
- **Public placement catalogs.** `adagents.json` can publish placement catalogs and publisher-scoped `placement_refs`; seller-private routing stays out of public placement schemas. → [`multitenant`](#track-5--l2-account-store-registry-multi-tenant).
- **Vendor-attested measurement.** `vendor_metric` optimization goals, per-product `vendor_metric_optimization`, reporting-coherence preconditions, and compliance coverage. → [`codegen`](#track-2--l0-types--codegen) + [`testing`](#track-9--testing--conformance).
- **Delivery and billing finality.** `reach_window`, `viewability.viewed_seconds`, windowed pull recovery, row-level delivery finality, `report_usage` finality, and `BILLING_OUT_OF_BAND`. → [`codegen`](#track-2--l0-types--codegen) + [`testing`](#track-9--testing--conformance).
- **Action discovery.** `allowed_actions[]`, `available_actions[]`, finer media-buy action enum values, `ACTION_NOT_ALLOWED`, and helper-level request decomposition in TS/Python. Java should expose typed helpers around `update_media_buy` mutations instead of forcing every adopter to re-parse action intent. → [`lifecycle`](#track-7--l3-lifecycle--transitions) + [`transport`](#track-3--l0-transport-mcp--a2a).

**Cross-SDK helper surface to match**

- **TS `@adcp/sdk@8.1` helper additions.** Root exports now include canonical creative format helpers, format projection/write-side helpers, `ensureGetProductsCacheScope()` / `validateGetProductsCacheScope()`, `parseWholesaleFeedWebhookNotification()` / `normalizeWholesaleFeedWebhookNotification()`, signal discovery helpers, `decomposeUpdateMediaBuy()` / `assertUpdateMediaBuyAllowed()`, per-tool type slices, SSRF-safe networking helpers, typed server `*Payload` aliases, and 3.1 compliance/cache selection in the runner. Java equivalents belong in the main `adcp`, `adcp-server`, and `adcp-testing` artifacts rather than new artifacts.
- **Python `adcp@6.x` helper additions.** Python now has version-routed validation, 2.5/3.0/3.1 beta schema caches, canonical-format projection, webhook proof-of-control helpers, wholesale feed sender, request-scoped capabilities hooks, unknown-field policy and hook composition, media-buy version handling/update actions, externally managed webhook signing capabilities, and permissive property resolution. Java server APIs should mirror the capability/hook seams even if implementation names differ.

**Authentication / discovery**

- **`AuthenticationRequiredError.challenge` + `probeAuthChallenge`** (7.2.0). Parses `WWW-Authenticate` on non-Bearer 401s; the parsed `{ scheme, realm?, scope?, error?, error_description? }` rides on the error so consumers can branch on auth scheme. Wired into MCP/A2A discovery and the A2A in-flight 401 path. → [`transport`](#track-3--l0-transport-mcp--a2a) + [`docs`](#track-14--docs-migration-troubleshooting).
- **HTTP Basic auth (`auth: { type: 'basic', … }`, `--auth-scheme bearer|basic`, RFC 7617)** (7.2.0). Adopters fronted by Apigee / Kong / AWS API GW / nginx `auth_basic` were unreachable on bearer-only. Java must accept user:pass auth config, validate at register-time, and inject `Authorization: Basic` via header merging (mutually exclusive with OAuth/bearer). → [`transport`](#track-3--l0-transport-mcp--a2a) + [`cli`](#track-13--cli).
- **`resolveAgentProperties` / `listAgentPropertyMap` / `canonicalizeAgentUrl`** + `AdAgentsPublisherPropertySelector` (7.2.0). Per-entry `authorization_type` dispatch over `adagents.json` (`property_ids` / `property_tags` / `inline_properties` / `publisher_properties` / `signal_ids` / `signal_tags`). Fixes a cross-SDK divergence vs. Python. Java must match Python's `_resolve_agent_properties`, not the pre-fix TS behavior. → [`multitenant`](#track-5--l2-account-store-registry-multi-tenant).
- **`validateAdAgents` with ads.txt `MANAGERDOMAIN` one-hop fallback** (7.2.0). New top-level entrypoint with `DiscoveryMethod` / `AdAgentsValidationResult` types. One hop only; `publisher → publisher` cycles rejected; `#noagents` honored. → [`multitenant`](#track-5--l2-account-store-registry-multi-tenant).
- **`MCPOAuthProvider` `allowHttp`** + RFC 9728 resource-URL handling (6.19.1). Local dev pattern. → [`transport`](#track-3--l0-transport-mcp--a2a) + [`docs`](#track-14--docs-migration-troubleshooting).

**Conformance / storyboard surface**

- **`storyboards_missing_tools` vs `storyboards_not_applicable` split** (7.0.0, breaking on TS). `ComplianceResult` shape changed. Java conformance harness must emit the split from day one. → [`testing`](#track-9--testing--conformance).
- **`AdcpErrorInfo.issues[]` as first-class field** (7.0.0) — per `core/error.json` 3.0 GA. → [`codegen`](#track-2--l0-types--codegen).
- **`RunnerNotice` / `NoticeCode` / `NoticeSeverity`** (7.1.0) — stable `code` values so CI gates and JUnit consumers don't parse prose `skip.detail` strings. `StoryboardResult.notices` always-present; `ComplianceResult.notices` optional and deduped by `code`. → [`testing`](#track-9--testing--conformance).
- **`ResponseSchemaValidationError` typed error class** (7.1.0) — attributes Zod schema rejects to `response_schema` and short-circuits step-scope invariants. Java equivalent: typed error from Jackson + json-schema-validator that the storyboard runner can branch on. → [`codegen`](#track-2--l0-types--codegen) + [`testing`](#track-9--testing--conformance).
- **`parallel_dispatch` step** (7.0.0) — fans out N concurrent dispatches against the same agent (`Promise.all` shape), grades the cross-response set; drives the AdCP 3.1 concurrent-retry / first-insert-wins phase of the idempotency storyboard. New check kinds (`cross_response_*`). In Java this maps to virtual threads on 21+ / bounded executor on 17–20, plus structured concurrency for the barrier. → [`testing`](#track-9--testing--conformance) + [`async-l3`](#track-6--l3-idempotency-async-tasks-webhooks).

**Idempotency / async wire codes**

- **`IDEMPOTENCY_IN_FLIGHT` (AdCP 3.1 wire code)** (7.0.0) — replaces legacy `SERVICE_UNAVAILABLE` for the in-flight branch; `recovery: transient`, `retry_after` derived from claim age (short for fresh, longer for slow handlers, capped at 5s). `IdempotencyCheckResult.kind === 'in-flight'` carries `retryAfterSeconds`. Java idempotency middleware must emit this and Java callers must auto-retry on `transient + retry_after`. → [`async-l3`](#track-6--l3-idempotency-async-tasks-webhooks).

**`comply_test_controller` / requirement autodetect**

- **`webhook_receiver` autodetect from storyboard token presence** (7.0.0). → [`comply`](#track-8--l3-comply_test_controller).
- **`request_signer` + `oauth_metadata` autodetect, pre-empts `oauth_discovery` cascade-skip** (7.1.0). → [`comply`](#track-8--l3-comply_test_controller).
- **`account-discovery` spec-conformance gate** (6.17.0) — gates around `get_adcp_capabilities` / `list_accounts` / `sync_accounts` advertisement. → [`comply`](#track-8--l3-comply_test_controller) + [`multitenant`](#track-5--l2-account-store-registry-multi-tenant).
- **Per-storyboard `requires:` gate** (`controller` / `seeded_state` / `real_wire`) + `--asserts-seeded-state` flag (6.17.0). → [`testing`](#track-9--testing--conformance).
- **Detailed skip-cause taxonomy** (`RunnerDetailedSkipReason`) (6.17.0). → [`testing`](#track-9--testing--conformance).

**`upstream-recorder` — new artifact surface (not on the RFC)**

- **`@adcp/sdk/upstream-recorder`** (added in 7.x). Producer-side reference middleware for the AdCP `upstream_traffic` storyboard check (spec PR #3816). Wraps the adapter's HTTP layer, redacts at record time (plaintext secrets never sit in memory), enforces per-principal isolation (spec HIGH security requirement), exposes `query()` that maps onto the controller's `UpstreamTrafficSuccess` shape. Sandbox-only by default; production-disabled is a no-op zero-overhead path.
- **Java fit:** lands in `adcp-server` as `UpstreamRecorder` SPI. Per-principal scoping uses `ScopedValue` (Java 21 baseline; see [Confirmed decisions](#confirmed-decisions)). HTTP wrapper integrates at the same boundary as outbound signing. → new sub-scope in [`async-l3`](#track-6--l3-idempotency-async-tasks-webhooks) (the recorder co-locates with webhook outbound HTTP). The RFC's `comply_test_controller` surface gains a `query_upstream_traffic` scenario served from the recorder.

**Codegen surface tightening (TS-only output shape, applies to Java codegen too)**

- **`Format.assets` named slot unions** (`IndividualAssetSlot` / `GroupAssetSlot` / `FormatAssetSlot`) with `asset_type` discriminator + per-slot `requirements` (6.19.0). Java codegen must emit equivalent sealed interfaces with the discriminator pattern. → [`codegen`](#track-2--l0-types--codegen).
- **`CreativeBuilderPlatform` doesn't advertise missing tools in `tools/list`** (7.0.0) — the platform doesn't lie about its capabilities. Java server-side handler must conditionally advertise tools based on adopter implementation. → [`transport`](#track-3--l0-transport-mcp--a2a).

**Security / SSRF posture (cross-cutting hardening)**

- The 6.16–6.17 line added a cross-cutting SSRF migration covering `detectProtocol`, `discoverAgentProfile`, `fetchAdAgentsJsonFromUrl`, the network-consistency-checker, the property-crawler, and the buyer-side discovery path. The baseline is: resolve DNS once, validate the full address set against address-guards, pin the connect to the first validated address (undici interceptor), `redirect: 'manual'`, 4 KiB body cap on probes. Java SDK must match this baseline on every outbound discovery call — not as a v1.x hardening pass, **as v0.1 baseline**. JDK `HttpClient` doesn't pin connect-addresses natively; Java needs a custom `Authenticator`/`Selector` shim or the equivalent at the `SocketChannel` boundary. → cross-cutting concern, lives in [`transport`](#track-3--l0-transport-mcp--a2a) with its own design doc before contributor pickup.

- **`tasks/cancel` fire-and-forget on buyer abort** (6.16.0). Aborted poll must POST a real-UUID `tasks/cancel` with `AbortSignal.timeout(5000)`, silently catch rejection (an aborted buyer must not have observability dependencies on cancel success). `signed-requests` sellers will 401 unsigned cancels — so this path threads through outbound signing. → [`transport`](#track-3--l0-transport-mcp--a2a) + [`signing`](#track-4--l1-signing).

### 3.1 beta impact on the milestone calendar

The 7.x / 8.x / 3.1 beta deltas don't move the M+12 GA line, but they do tighten the early release gates:

- v0.1 release gate **adds**: D23 target bundle ingestion (`3.1.0-beta.5`, or 3.1 GA if cut before the codegen PR), multi-bundle validator selection, wire `adcp_version` support for stable and prerelease tokens, envelope `status` stamping, SSRF baseline (DNS pin, address-guards, redirect:manual, body cap) on all discovery probes, `WWW-Authenticate`-aware error envelope, HTTP Basic config support, and the `storyboards_missing_tools` / `storyboards_not_applicable` / `steps_not_selected` split in runner output. This accepts a realistic v0.1 slip from M+2 to M+3 rather than deferring the security/auth baseline.
- v0.2 gate **adds**: typed webhook token echo, endpoint proof-of-control foundations, and enough signing metadata to support externally managed webhook-signing capabilities without assuming the SDK owns every key.
- v0.3 gate **adds**: `IDEMPOTENCY_IN_FLIGHT` wire code with claim-age-derived `retry_after` cap, universal request `idempotency_key` handling, `resolveAgentProperties` / `validateAdAgents` / `MANAGERDOMAIN` fallback, wholesale feed versioning/cache-scope semantics, canonical format projection helpers, and `SignalRef` / product-scoped signal targeting.
- v0.4 gate **adds**: durable account-level webhook configs with proof-of-control, wholesale feed webhook parsing/emission, `upstream-recorder` SPI, `query_upstream_traffic` controller scenario, `parallel_dispatch` storyboard step, full `RunnerNotice` taxonomy, advisory `errors[]` handling, and the 3.1 compliance storyboard additions.

## Harness first — what lands before contributors are pulled in

Open question from the user: should we (the founder pair) build a harness before opening tracks to contributors?

**Recommendation: yes, ~2 weeks of scaffold, then open tracks.** Empty repos lose contributors. A skeleton with "your environment compiles, your tests run, here's where to start" loop is the difference between a track claim turning into a PR vs. turning into a Slack thread.

Hard line: **scaffold the build, leave the rooms empty.** Don't pre-build L1 / L2 / L3 surface — that locks in design before the contributors who'll own those tracks weigh in.

### Pre-contributor harness scope

| Item | Why | Builds toward |
|---|---|---|
| Gradle multi-module skeleton (5 published artifacts + 2 bridge modules `adcp-reactor` / `adcp-mutiny` as empty stubs) | Stable multi-module graph from day one; package names locked; contributors don't fight `settings.gradle.kts` reviews | [`infra`](#track-1--build-repo-release-infra) |
| Schema-bundle fetcher Gradle task: download `{version}.tgz`, `cosign verify-blob`, extract to build dir | Codegen has something to point at; SSRF/signing posture established before any HTTP code lands | [`infra`](#track-1--build-repo-release-infra) |
| Codegen MVP: emit records + builder records for **one or two** request/response pairs (e.g. `GetProductsRequest` / `GetProductsResponse`) | Proves the generator architecture, locks in the `*Request`/`*Response` naming invariant, gives contributors real Java to import. Full coverage stays in [`codegen`](#track-2--l0-types--codegen). | [`codegen`](#track-2--l0-types--codegen) |
| Prototype the two open MCP-SDK questions on `io.modelcontextprotocol.sdk:mcp:1.1.2` (per D9): can `mcp-core`'s servlet streamable-HTTP server transport run without Jetty/Tomcat? Is `mcp-json-jackson2` feature-equivalent to the Jackson 3 variant? | D9 picked the SDK; these two are the only unresolved bits before [`transport`](#track-3--l0-transport-mcp--a2a) opens for claim. | [`transport`](#track-3--l0-transport-mcp--a2a) |
| Sonatype OSSRH namespace claim for `org.adcontextprotocol` + foundation GPG key + key-server publication | Slow-path ticket (1–5 business days); start Week 1 even though first publish waits for v0.3 (per D6). Don't block v0.3 on a stalled OSSRH ticket. | [`infra`](#track-1--build-repo-release-infra) |
| SSRF-safe `HttpClient` wrapper skeleton (DNS pin, address-guards, redirect:manual, body cap) | Baseline TS/Python security posture. JDK `HttpClient` doesn't pin natively; this needs a design doc + skeleton before contributors touch outbound HTTP. | [`transport`](#track-3--l0-transport-mcp--a2a) |
| Storyboard CI gate shell: GitHub Actions on JDK 21, runs the runner against the `@adcp/sdk/mock-server`, even if the runner currently asserts only "we reached the server" | The v0.1 release gate is "storyboards green in CI." Standing it up empty and having it pass keeps contributors honest as L0 fills in — every PR is measured against the gate. | [`infra`](#track-1--build-repo-release-infra) + [`testing`](#track-9--testing--conformance) |
| Repo conventions: `CONTRIBUTING.md` (track-claim flow + IPR pointer per D19), `.github/ISSUE_TEMPLATE/track-claim.md`, PR template, `CLAUDE.md` for agent contributors | The track-claim issue template is the actual contributor onboarding doc | [`docs`](#track-14--docs-migration-troubleshooting) |
| AAO IPR caller workflow at `.github/workflows/ipr.yml` (per D19) + foundation admin installs the IPR Bot on this repo + adds it to the `IPR_APP_ID` / `IPR_APP_PRIVATE_KEY` org-secret scope | Required-status check `IPR Policy / Signature` is the gate on every PR (per D21) — must be working before contributor PRs arrive | [`docs`](#track-14--docs-migration-troubleshooting) + [`infra`](#track-1--build-repo-release-infra) |
| DNS TXT record on `adcontextprotocol.org` for Sonatype namespace verification (per D20) | Foundation-admin action; pairs with the OSSRH ticket. Without it the ticket stalls. | [`infra`](#track-1--build-repo-release-infra) |
| Branch protection on `main` per D21 (required reviews, required checks, no force-push, no admin bypass) | Locks the trunk-based model (D17) before tracks open | [`infra`](#track-1--build-repo-release-infra) |
| Commitlint + Changesets wiring (per D18) | Adopter expectations are set by `@adcp/sdk`'s release notes; matching the workflow shape means humans and tools read both SDKs' changelogs the same way | [`infra`](#track-1--build-repo-release-infra) + [`docs`](#track-14--docs-migration-troubleshooting) |

### Explicitly **not** in the harness

These look tempting to "get started on" but pre-building them locks in design that should be a track owner's call:

- L1 RFC 9421 signing — too much spec surface; lives in [`signing`](#track-4--l1-signing).
- Full type generation — [`codegen`](#track-2--l0-types--codegen)'s job once MVP is proven.
- Spring Boot starter — downstream, blocks on L1/L2/L3 surface.
- Account store / idempotency / webhook code — design-heavy; needs the track owner's voice.
- Lifecycle YAML coordination — depends on TS/Python maintainer buy-in (RFC Decision 6).

### Ordering

- **Week 1 (founder pair):** Gradle skeleton, schema fetcher, codegen MVP, repo conventions, confirmed-decision index, CI shell.
- **Week 2 (founder pair + advisors):** MCP SDK pick recorded in the decision index. SSRF wrapper skeleton + `specs/<topic>.md` design doc. First storyboard CI run green-against-empty. Open the first 3–4 track-claim issues publicly.
- **Week 3+:** Contributors arrive against a repo where `./gradlew check` passes and CI tells them whether their PR broke conformance. Tracks land in dependency order.

This means the founder pair owns the [`infra`](#track-1--build-repo-release-infra) track end-to-end and the first slice of [`codegen`](#track-2--l0-types--codegen) and [`transport`](#track-3--l0-transport-mcp--a2a). Everything else opens for claim once the harness is green.

## How to read this plan

The roadmap below has two axes:

- **Milestones** (v0.1 → v1.0) — vertical slices tied to the RFC roadmap. Each milestone has a date target (M+N from project kickoff) and a release gate.
- **Tracks** — parallel workstreams that contributors can claim independently. A track spans multiple milestones.

Contributors claim a **track**, not a milestone. Tracks have explicit dependencies; if track A blocks track B at milestone N, that's called out so we don't fail to sequence.

Each track entry has:

- **Scope** — what's in.
- **Out of scope** — what isn't, to keep the track bounded.
- **Depends on** — tracks that must land first.
- **Size** — rough person-month estimate (eng-months of focused work, not calendar time).
- **Owner** — `TBD` until a contributor claims it. Claim by opening an issue with the track ID.

## Milestones

| Milestone | Target | Release gate |
|---|---|---|
| v0.1 alpha | M+3 | L0 surface compiles against the D23 target bundle (`3.1.0-beta.5`, or 3.1 GA if cut before the codegen PR), wire-version negotiation works for stable and prerelease tokens, multi-bundle validators can serve 3.0 and 3.1 traffic, SSRF/auth discovery baseline lands, storyboards green against reference mock-server in CI. Local Gradle artifacts only (per D6 — first Maven Central publish at v0.3). |
| v0.2 alpha | M+4 | L1: RFC 9421 signing/verification, AWS+GCP KMS providers (lazy-init, tenant-aware per-`adcp_use` key selector per D22), webhook signing, typed webhook token / proof-of-control foundations |
| v0.3 alpha | M+6 | L2 + partial L3: account store, idempotency, async tasks, wholesale feed cache-scope semantics, canonical-format / signal-targeting helpers, Spring Boot starter alpha. **First Maven Central publish** (per D6). |
| v0.4 beta | M+9 | Full L3: transition validators, webhook emission, wholesale feed webhooks, `comply_test_controller`, A2A transport, 3.1 compliance bundle parity |
| v1.0 GA | M+12 | L0–L3 parity, Reactor + Mutiny adapters, Kotlin co-release, Maven Central GA |

The RFC's M+12 target is the realistic line. Pre-committing M+9 and slipping is worse than committing M+12 and beating it. Slippage concentrates on: MCP Java SDK churn, RFC 9421 canonicalization edge cases, shared lifecycle YAML coordination, Spring Boot starter scope creep.

## Tracks

### Track 1 — Build, repo, release infra

**ID:** `infra` | **Owner:** TBD | **Size:** 1.0 person-month across the year

**Scope:**

- Gradle multi-module skeleton matching the 5 artifacts (`adcp`, `adcp-server`, `adcp-testing`, `adcp-spring-boot-starter`, `adcp-cli`).
- `adcp-reactor` and `adcp-mutiny` modules wired from the start (empty until [`async-bridges`](#track-12--reactor--mutiny-adapters)) so the multi-module graph is stable.
- `Automatic-Module-Name` set on every JAR manifest.
- Gradle reproducible-jar config, lockfiles checked in.
- Codegen Gradle task that downloads the protocol tarball, verifies with `cosign verify-blob`, extracts schemas, hands off to [`codegen`](#track-2--l0-types--codegen).
- Sonatype OSSRH namespace + GPG key set up harness Week 1; first Maven Central publish at v0.3 (per D6). Sigstore migration tracked as a follow-up.
- GitHub Actions on JDK 21, run storyboard CI against `@adcp/sdk/mock-server` (the v0.1 gate; storyboard runner from [`testing`](#track-9--testing--conformance) plugs in).
- JavaDoc + sources jars on every release.

**Out of scope:** GraalVM native-image (post-v1.0), JPMS modules (opt-in only).

**Depends on:** nothing. This track unblocks everything else and should land in week 1.

**Milestone targets:** v0.1 needs Gradle multi-module + CI on JDK 21 + green storyboard runner against the sidecar `npx adcp mock-server`. v0.3 adds the first Maven Central publish (per D6). Sigstore signing migration can land any time before v1.0.

---

### Track 2 — L0 types & codegen

**ID:** `codegen` | **Owner:** TBD | **Size:** 2.0 person-months

**Scope:**

- Custom codegen on Eclipse JDT or JavaPoet, emitting Java records for value/response types and builder-records for request types.
- Generator invariant: `*Request` types always have builders; `*Response` types are records and never do (RFC §Type generation).
- Generator invariant: open protocol vocabularies emit an unknown-safe shape (`sealed Known<T> / Unknown(rawValue)` by default), not strict enums. See [`specs/codegen-open-enums.md`](specs/codegen-open-enums.md).
- Polymorphic envelope handling (Jackson `@JsonTypeInfo` / `@JsonSubTypes`).
- `x-adcp-*` annotation post-processors mirroring `scripts/generate-types.ts` in `adcp-client`.
- Version pinning support (`adcp-v2-5` co-existence namespace): generates frozen v2.5.1 types under `org.adcontextprotocol.adcp.generated.v2_5.*` alongside primary v3.0 and v3.1 namespaces. All namespaces coexist on the classpath. v2.5.1 schemas fetched from the GitHub source archive (`adcontextprotocol/adcp@v2.5.1/static/schemas/source`) since pre-3.0 bundles were never published to the CDN.
- Multi-bundle schema registry: runtime validator lookup by served wire version (`3.0`, `3.1`, `3.1-beta.N`), with the full bundle pin stored separately from the wire value. `ADCP_VERSION` cannot remain the only schema selector.
- Unknown-safe enums for open protocol vocabularies, especially `ErrorCode`; strict Java enums are acceptable only where the spec vocabulary is intentionally closed.
- Generated response payload aliases for server handlers, so framework adapters stamp protocol-envelope fields centrally instead of making adopter code return top-level `status`, `adcp_version`, and related envelope fields by hand.
- 3.1 shape changes: required envelope `status`, `media_buy_status`, governance `verdict` / `outcome_state`, `SignalRef`, `format_option_refs`, product-scoped signal targeting groups, wholesale feed version/cache fields, delivery/billing finality, vendor-metric goals, action-discovery enums, and typed webhook token payloads.
- JSpecify `@Nullable` annotations on every public type. No `Optional<T>` returns.
- Schema validator wrapper around `com.networknt:json-schema-validator`.
- Schema-bundle accessor (runtime, resources jar; build-time loader lives in [`infra`](#track-1--build-repo-release-infra)).

**Out of scope:** Kotlin source generation (handled by [`kotlin`](#track-11--kotlin-extensions)).

**Depends on:** `infra` (codegen Gradle task hookpoint).

**Milestone targets:** v0.1 needs full generated type coverage for the L0 surface, D23 target-bundle coverage (`3.1.0-beta.5`, or 3.1 GA if it cuts first), multi-bundle validator selection, and stable/prerelease wire version normalization wired into transport.

---

### Track 3 — L0 transport: MCP + A2A

**ID:** `transport` | **Owner:** @MichielDean (#17) | **Size:** 1.5 person-months

**Scope:**

- **MCP:** depend on `io.modelcontextprotocol.sdk:mcp` pinned `1.1.2` (per D9). Used by both `adcp` (caller) and `adcp-server` (agent). Plan a deliberate 2.x migration PR ~6 months out (the 2.0 line removes sealed interfaces from message types, replaces `JsonSchema` with `Map`, flips the tool-input-validation default, removes server-transport builder methods). License is MIT — flagged for foundation position. Two open prototype questions land harness Week 1: whether the servlet-based streamable-HTTP server transport works without pulling Jetty/Tomcat, and whether `mcp-json-jackson2` is feature-equivalent to the Jackson 3 module.
- **A2A pre-1.0:** minimal SSE consumer + JSON-RPC framer in `adcp-server`. Default: keep types in-tree until `a2a-java` cuts its first stable release (≥ 1.0.0), then migrate in one shot (RFC Open Question 3).
- **A2A post-1.0:** swap transport to `a2aproject/a2a-java`; deprecate the in-tree fallback in the next minor.
- HTTP transport on `java.net.http.HttpClient`. No third-party HTTP client in the core.
- Jackson `ObjectMapper` with `StreamReadConstraints` / `StreamWriteConstraints` widened to AdCP-shaped defaults (RFC §JSON).
- Wire `adcp_version` on every request/response, accepting and echoing release-precision stable values (`3.1`) and normalized prerelease values (`3.1-beta.N`) while storing the full bundle pin (`3.1.0-beta.N`) separately from the wire token. Keep the legacy `adcp_major_version` mirror through 3.x. Server dispatch validates requested version before handler execution and returns typed `VERSION_UNSUPPORTED` with `supported_versions`.
- Central response-envelope stamping for sync success, submitted, failed, and advisory-success responses. The seam is a `ResponseEnvelope<P>` record materialized by the server adapter after adopter handler payloads return and before signing/serialization; outbound signing canonicalizes the stamped envelope. Payload `errors[]` advisories like `STALE_RESPONSE` must not be thrown as transport failures.
- Auth/discovery parity with TS/Python: Basic/Bearer config, `WWW-Authenticate` challenge parsing, private-agent-card auth forwarding, and the standard A2A Agent Card path.
- Server handler payload aliases and unknown-field policy hooks matching the Python/TS ergonomics: adopters return domain payloads; the framework owns envelope fields and strictness policy.
- **No `*Async` mirror methods.** With JDK 21 as baseline, virtual threads make the sync API scale natively; the RFC's 12-method `*Async` mirror surface is dropped (see [Confirmed decisions](#confirmed-decisions)). Adopters who explicitly want `CompletableFuture` wrap individual calls themselves.

**Out of scope:** OkHttp / Apache HttpClient 5 adapters (post-v1.0 on demand).

**Depends on:** `codegen` for the request/response types.

**Milestone targets:** v0.1 needs MCP transport plus version negotiation, envelope stamping, SSRF baseline on discovery probes, `WWW-Authenticate` challenge parsing, Basic auth config, and private-agent-card auth forwarding. v0.4 swaps in upstream `a2a-java` if its 1.0 has cut by then; otherwise the in-tree fallback ships at v1.0 with the swap-trigger documented.

---

### Track 4 — L1 signing

**ID:** `signing` | **Owner:** TBD | **Size:** 1.5 person-months

**Scope:**

- Hand-rolled RFC 9421 canonicalizer (it's small and spec-tight; `org.tomitribe:http-signatures` is the wrong spec). Verifier test harness mirrors the TS one.
- `SigningProvider` SPI via `META-INF/services/`. API shape takes explicit `SigningContext` rather than a single `AdcpUse`; receivers enforce purpose at JWK `adcp_use`.
- Tenant-aware key selection at the signing boundary (D22). The API cannot model one global key per `adcp_use`: multi-tenant operators need one JWKS endpoint with distinct `kid` values per publisher tenant under the same `adcp_use`. The v0.2 signing surface freezes `SigningContext` in [`specs/signing-context.md`](specs/signing-context.md); v0.3 connects it to `AccountStore` tenant resolution.
- In-process provider via JCA Ed25519 / ECDSA. **No Bouncy Castle in core** — JDK 21 has Ed25519 natively.
- AWS KMS provider via `software.amazon.awssdk:kms`. Lazy-init.
- GCP KMS provider via `com.google.cloud:google-cloud-kms`. Lazy-init.
- Optional `adcp-signing-bouncycastle` artifact for FIPS environments.
- Outbound webhook signing wired into [`async-l3`](#track-6--l3-idempotency-async-tasks-webhooks).
- Pre-deploy KMS probe as a separate CLI command, not part of boot critical path.

**Out of scope:** Azure KMS (post-v1.0 on demand). Hardware HSM integration beyond JCA (post-v1.0).

**Depends on:** `transport` (signing wraps HTTP-level requests).

**Milestone targets:** v0.2 ships RFC 9421 + AWS+GCP KMS + webhook outbound signing, including typed webhook token echo, endpoint proof-of-control foundations, and externally managed webhook-signing metadata.

---

### Track 5 — L2 account store, registry, multi-tenant

**ID:** `multitenant` | **Owner:** TBD | **Size:** 1.0 person-month

**Scope:**

- `AccountStore` SPI. Reference impls:
  - `InMemoryAccountStore` (tests).
  - `JdbcAccountStore` against a Flyway/Liquibase-managed schema.
  - Optional `JpaAccountStore` if Spring Data JPA shops claim it.
- `RegistryClient` SPI for agent-registry / brand-resolution lookup. Default impl points at the public AAO registry.
- Multi-tenant principal resolution wired through the request handler.
- Per-tenant signing context export for D22: the resolved account/principal carries enough stable tenant identity for the L1 signer to choose the correct `kid` when multiple publisher tenants share one operator JWKS endpoint.
- `adagents.json` resolver parity with TS/Python: authorization-type dispatch over `property_ids`, `property_tags`, `inline_properties`, `publisher_properties`, `signal_ids`, and `signal_tags`; one-hop ads.txt `MANAGERDOMAIN` fallback; revoked-domain semantics; permissive property resolution mode for operators that need diagnostics instead of hard fail.
- Public placement catalog support from `adagents.json`, including publisher-scoped `placement_refs` and same-file `format_option_id` validation.
- Wholesale product/signal cache model: `cache_scope`, `wholesale_feed_version`, `if_wholesale_feed_version`, pricing-version tokens, and public-vs-account overlay invalidation.
- Product-scoped signal targeting support: canonical `SignalRef`, `included_signals`, `signal_targeting_options`, `signal_targeting_rules`, and package-level `targeting_overlay.signal_targeting_groups`.
- Sandbox/live boundary enforcement at the `AccountStore` (so `comply_test_controller` calls return `COMPLY_NOT_AVAILABLE` on production accounts per spec).
- Agent-card publication helper.

**Out of scope:** Caller-side credential presentation beyond what L0 already covers (folded into [`transport`](#track-3--l0-transport-mcp--a2a)).

**Depends on:** `codegen` for principal / account types.

**Milestone targets:** v0.3 alpha ships full L2 plus wholesale feed cache-scope semantics.

---

### Track 6 — L3 idempotency, async tasks, webhooks

**ID:** `async-l3` | **Owner:** TBD | **Size:** 2.0 person-months

**Scope:**

- `IdempotencyStore` SPI with in-memory, JDBC, Redis (Lettuce) refs.
- `IdempotencyConflict` as a **sealed type** that structurally cannot carry a payload echo (read-oracle threat model from `L1/security.mdx#idempotency`).
- Byte-identical replay within TTL: store API takes and returns raw bytes alongside the typed response so replay can't accidentally re-serialize and drift.
- `TaskStore` SPI with generic artifact type — the compiler enforces that a task's terminal artifact carries the **original tool's response shape**, not a generic task envelope.
- `WebhookEmitter` two-executor pattern (RFC §Async-task store and webhooks):
  - `scheduler`: small platform-thread `ScheduledExecutorService` (default size 1–2). Pure scheduling.
  - `dispatcher`: separate executor that runs HTTP delivery. Default: `Executors.newVirtualThreadPerTaskExecutor()` (JDK 21 baseline).
  - Both executors injectable on `WebhookEmitter.builder()`.
- Async-result polling shape on the caller side.
- Error-recovery classification consumed from the spec's `error-code.json` `enumMetadata` (PR #3738). SDK consumes; doesn't re-derive.
- `IDEMPOTENCY_IN_FLIGHT` with transient recovery and claim-age-derived `retry_after`; Java callers must retry without minting a new key.
- Universal `idempotency_key` handling for read and write tasks, including byte-identical replay and `replayed` advisory fields on cached read responses.
- Durable account-level webhook subscriptions from `sync_accounts.accounts[].notification_configs[]`, with `subscriber_id` stable replace/upsert semantics, endpoint proof-of-control, paused-config behavior, and typed webhook token echo.
- Wholesale feed webhook parse/normalize/send helpers for `product.*`, `signal.*`, and `wholesale_feed.bulk_change`; repair path is always `get_products` / `get_signals`.
- Advisory-success payload handling for stale cached upstreams (`STALE_RESPONSE`) and SDK-generated projection warnings.

**Out of scope:** Persistence migrations beyond reference schemas (adopter responsibility).

**Depends on:** `codegen`, `signing` (webhook outbound), `multitenant` (sandbox boundary).

**Milestone targets:** Partial in v0.3 (idempotency + async tasks + cache-scope). Full webhook emitter and wholesale feed webhooks in v0.4.

---

### Track 7 — L3 lifecycle & transitions

**ID:** `lifecycle` | **Owner:** TBD | **Size:** 1.5 person-months (Java-side; coordination cost with TS/Python maintainers is separate)

**Scope:**

- Decide between RFC paths 1 and 2 (RFC §Lifecycle and transition validation). Recommendation in RFC: path 2 (lead the cross-SDK shared YAML lifecycle source). Decision depends on TS + Python maintainer commitment — see [Decisions wanted](#decisions-wanted).
- If path 2: author lifecycle YAMLs in the spec repo for the 7 resources (`MediaBuy`, `Creative`, `Account`, `SISession`, `CatalogItem`, `Proposal`, `Audience`). Wire all three SDKs to consume them.
- Transition validator API takes `(action, from, to)`, not `(from, to)` — `NOT_CANCELLABLE` precedence over `INVALID_STATE` requires the action.
- 3.1 action-discovery helper layer: parse `allowed_actions[]` and `available_actions[]`, model `update_media_buy` mutations as sealed request subtypes where possible, expose `decomposeUpdateMediaBuy()` only as a compatibility fallback for legacy/non-sealed shapes, and surface `ACTION_NOT_ALLOWED` with structured details before handler side effects.
- `TransitionGuard` SPI for adopter preconditions. Guards run **after** the spec edge check; can never relax a spec edge.
- Guard narrowing protection: guards declare which edges they touch; conformance harness fails if a sandbox account's guards narrow any edge the storyboards exercise (RFC Open Question 7).

**Out of scope:** Adopter-side guard implementations (L4 concern).

**Depends on:** `codegen` for resource types, `testing` for harness integration.

**Milestone targets:** v0.4. Path 2 coordination starts immediately if WG signs off.

---

### Track 8 — L3 `comply_test_controller`

**ID:** `comply` | **Owner:** TBD | **Size:** 0.75 person-month

**Scope:**

- `seed_*` / `force_*` / `simulate_*` controller surface matching `@adcp/sdk`'s `/conformance` and `/compliance`.
- Sandbox-only enforcement wired at the `AccountStore` boundary (production → `COMPLY_NOT_AVAILABLE`).
- 3.1 controller additions: `force_upstream_unavailable`, typed `simulate_delivery` params for reach/frequency/reach_window/viewability, webhook proof-of-control probes, stale-response exercises, and product-signal / canonical-format coverage.
- Storyboard hint fix-plan format (`Diagnose / Locate / Fix / Verify`) surfaced in adopter-facing test reports.

**Depends on:** `multitenant` (sandbox boundary), `codegen`.

**Milestone targets:** v0.4.

---

### Track 9 — Testing & conformance

**ID:** `testing` | **Owner:** TBD | **Size:** 1.5 person-months

**Scope:**

- `adcp-testing` artifact, JUnit 5 first-class.
- `AdcpAgentExtension` — JUnit 5 extension that boots an in-process agent (or wraps an adopter's agent) for storyboard runs.
- `StoryboardRunner` — Java port of TS `runStoryboard`. Reads YAML storyboards from the protocol bundle, runs them against an agent under test, asserts wire conformance.
- **Mock-server forwarding adapter** (critical — RFC §`comply_test_controller`): storyboards certify against the shared reference mock-server, not an in-process Java mock. Without this, storyboards run against the SDK's own L4 stub instead of the spec-compliance oracle, and certification fails.
- 3.1 runner output shape: `steps_not_selected`, `not_selected_by_reason`, selected-but-skipped separation, `storyboards_missing_tools` / `storyboards_not_applicable`, `RunnerNotice`, and structured response-schema validation errors.
- 3.1 storyboard input gating: consume `required_any_of_tools` as a storyboard-schema gate and surface failures via `requirement_unmet` detail. Keep account-discovery synthesized gating until the upstream bundle fully migrates.
- Multi-bundle validator selection for compliance runs: a 3.1 runner can execute supplied 3.0 bundles with matching schema cache instead of assuming the installed SDK's primary pin.
- 3.1 storyboard primitives: `parallel_dispatch`, `cross_response_*` checks, advisory `errors[]` assertions, stale-response placement, read-tool idempotency envelope fields, canonical-format projection, product-signal targeting, vendor-metric optimization, reach-window/viewability delivery reporting, billing finality, and action discovery.
- `MockAgent` for callers under test (buyer-side mirror).
- `Personas` port of `/testing/personas`.
- Signing test fixtures (port of `/signing/testing`).

**Out of scope:** Test infrastructure beyond JUnit 5 (TestNG support is adopter's problem if they want it).

**Depends on:** `codegen`, `transport`. The mock-server forwarding adapter is the v0.1 release gate — without it, CI claims conformance it doesn't have.

**Milestone targets:** v0.1 ships the storyboard runner, mock-server forwarding adapter, 3.1 output splits/notices, and multi-bundle validator selection for compliance runs. Conformance test surface expands at each subsequent milestone as L1/L2/L3 land.

---

### Track 10 — Spring Boot starter

**ID:** `spring` | **Owner:** TBD | **Size:** 1.0 person-month

**Scope:**

- `adcp-spring-boot-starter` auto-configures: handler, Jackson `ObjectMapper`, signing provider, account store.
- Micrometer `MeterRegistry` integration **if on classpath**. Metric names: `adcp.tool.duration`, `adcp.signing.verify.failures`, etc.
- Actuator `AdcpHealthIndicator` **if on classpath** — reports signing-key reachability + account-store reachability.
- Spring properties for tunables: `adcp.jackson.max-string-length`, etc.
- Spring Security integration as a **documented recipe**, not autoconfig (RFC §Server framework integration). Decision on `adcp-spring-boot-starter-security` deferred to v0.3 feedback.
- `jakarta` only, Spring Boot 3.x floor (per D7). Document the floor in the README so SB 2.7 shops don't burn an hour on the first import.

**Out of scope:** Quarkus / Micronaut / Servlet adapters (post-v1.0 on demand).

**Depends on:** `transport`, `signing`, `multitenant`, `async-l3`.

**Milestone targets:** Alpha in v0.3, polished by v1.0.

---

### Track 11 — Kotlin extensions

**ID:** `kotlin` | **Owner:** TBD | **Size:** 0.75 person-month

**Scope:**

- `adcp-kotlin` extension artifact on top of the Java surface.
- Coroutine `suspend fun` extension wrappers around the sync API.
- DSL builders for request types.
- Nullability already correct because the Java surface is JSpecify-annotated.

**Out of scope:** Independent Kotlin SDK. Kotlin remains a thin layer on Java.

**Depends on:** Stable Java public surface (waits for v1.0 freeze of L0–L3 APIs).

**Milestone targets:** Co-released with v1.0.

---

### Track 12 — Reactor & Mutiny adapters

**ID:** `async-bridges` | **Owner:** TBD | **Size:** 0.5 person-month

**Scope:**

- `adcp-reactor` — wraps the sync surface in `Mono.fromCallable(...)` on a bounded elastic scheduler.
- `adcp-mutiny` — Quarkus equivalent.
- **Both at GA**, not fast-follow (RFC §Async model — WebFlux shops left to wrap the sync API will own that complexity forever).

**Depends on:** Stable Java public surface.

**Milestone targets:** v1.0.

---

### Track 13 — CLI

**ID:** `cli` | **Owner:** TBD | **Size:** 0.5 person-month

**Scope:**

- `adcp-cli` runnable jar. Commands: `adcp <agent> [tool] [payload]`, `adcp storyboard run`, `adcp grade`.
- Pre-deploy KMS probe command (from `signing`).
- Homebrew tap as a Java-leads add.

**Out of scope:** GraalVM native-image (post-v1.0 — RFC §Reference).

**Depends on:** `transport`, `signing`, `testing`.

**Milestone targets:** Alpha in v0.3, polished by v1.0.

---

### Track 14 — Docs, migration, troubleshooting

**ID:** `docs` | **Owner:** TBD | **Size:** 1.0 person-month spread across milestones

**Scope:**

- JavaDoc on every public type. Generated and published.
- Migration guides for the four audiences (RFC §Migration path):
  1. Hand-rolled JVM agents.
  2. Python sidecar shops.
  3. Kotlin/JVM agents on Spring Boot.
  4. New JVM agents.
- Troubleshooting docs for the 8 RFC §Spec gotchas, especially the Jackson `StreamReadConstraints` first-hour bounce and Spring Boot 2.7 `NoSuchMethodError`.
- "Things we'd tell a new contributor" doc derived from the gotchas list.

**Depends on:** Each track contributes its slice of docs.

**Milestone targets:** Doc completeness gates v1.0 GA.

## Track dependency graph

```
infra ──┬─→ codegen ──┬─→ transport ──┬─→ signing ──┬─→ async-l3 ──┬─→ spring
        │             │               │             │              │
        │             ├──→ testing ←──┘             │              │
        │             │                             │              │
        │             ├─→ multitenant ──────────────┴─→ lifecycle  │
        │             │                                 │          │
        │             │                                 └─→ comply │
        │             │                                            │
        │             └─→ (stable surface) ─→ kotlin               │
        │                                  ─→ async-bridges        │
        │                                  ─→ cli  ────────────────┘
        │
        └─→ docs (cross-cutting)
```

## Claiming a track

Open an issue titled `[track:<id>] <your name> claims <track name>`. Include:

- Your prior JVM experience (records, sealed types, Jackson, Spring Boot, RFC 9421 / signing if relevant).
- Estimated availability per week.
- Which milestone you're committing to first.
- Whether you can attend the working-group sync.

A single contributor can claim multiple non-conflicting tracks (e.g. `cli` + `docs`). High-coupling tracks (`async-l3` + `lifecycle`) benefit from a single owner pair.

## Decisions wanted (blockers before scaling contributor count)

In priority order, from the RFC. Items marked **DONE** are locked in [Confirmed decisions](#confirmed-decisions); kept here as numbered references so RFC readers can map across.

1. **Funding / staffing.** A contributed engineer at 50%+ for ~12 months, plus a named WG maintainer with merge rights, plus 2–3 design partners committed v0.1 → v0.4. Without all three, the RFC says decline and revisit at next major.
2. **Design partners.** 2–3 JVM shops with letters of intent to ship on the SDK in 2026.
3. **WG vote** on Java as the fourth officially supported language.
4. **Maintainer.** Named owner with merge rights post-GA.
5. ~~MCP Java SDK choice.~~ **DONE** — D9.
6. **Cross-SDK lifecycle YAML buy-in.** TS + Python maintainers willing to consume a shared source. Decides `lifecycle` path 1 vs. path 2.
7. ~~`javax` vs `jakarta` floor.~~ **DONE** — D7.
8. ~~Reactor/Mutiny + Kotlin at GA vs. fast-follow.~~ **DONE** — D13 (Reactor + Mutiny at GA) and D14 (Kotlin at GA, thin extension).

Additional decisions added post-RFC that remain open:

9. **MIT-licensed dependency position.** D9 picked the MIT-licensed `io.modelcontextprotocol.sdk`. License is compatible with Apache 2.0 downstream use, but the foundation may want an explicit position on accepting MIT deps in officially supported SDKs.
10. **Funding model shape.** RFC framing (contributed engineer at 50%+ for ~12 months + named maintainer + 2–3 design partners) is the right ask; whether it's pooled member funding, single-anchor-org contribution, or foundation grant is open.
11. **Design partner outreach.** Anchor candidates by audience segment: one publisher running Spring Boot, one SSP, one broadcaster middleware team, and one EU ad-server vendor on Spring Boot 3.x / OAuth 2.1 / multi-tenant signing shape. ADvendio is a concrete reviewer candidate in that last segment, but has not committed engineering time or an LOI; still need 2–3 committed design partners before scaling.
12. **WG vote timing.** Recommendation: hold the vote at v0.1 alpha milestone (concrete working code) rather than now (abstract commitment).

## What's not in this plan (yet)

- Hiring or sourcing the contributed engineer / design partners — that's a foundation-level conversation, not a planning artifact.
- An RFC for the cross-SDK lifecycle YAML — should be authored separately once TS + Python maintainers are at the table.
- A v1.x roadmap (Quarkus, Micronaut, Servlet, OkHttp, Apache HttpClient 5, Azure KMS, GraalVM, JPMS modules). All deferred to post-v1.0 demand.

## Status

| Stage | Status |
|---|---|
| RFC merged on adcontextprotocol/adcp | ✅ (PR #4279, 2026-05-13) |
| RFC imported to this repo | ✅ ([docs/rfc/java-sdk-rfc.md](docs/rfc/java-sdk-rfc.md)) |
| Implementation plan drafted | ✅ (this doc) |
| Confirmed decisions | ✅ D1–D21 locked; D22 + D23 framing locked, shape/value details captured in this roadmap and linked specs. |
| Protocol parity check | 🟡 Behind — local Java pin is `3.0.11`; latest upstream check is AdCP `3.1.0-beta.5`, TS `@adcp/sdk@8.1.0-beta.13`, Python `adcp@6.3.0b4` on `3.1.0-beta.4`. |
| Funding / staffing confirmed | ⏳ Decision pending |
| Tracks claimed | 3 / 14 — `infra` (Track 1, #2), `codegen` (Track 2, #11), `transport` (Track 3, #17) |
| Pre-contributor harness | 🟡 In progress — Gradle skeleton, codegen MVP, SSRF skeleton, schema fetcher, mock-server CI gate, IPR workflow, commitlint, changesets, MCP prototype findings all landed. Foundation admin actions outstanding: IPR Bot install, DNS TXT for Sonatype, @MichielDean collaborator. |
| v0.1 alpha | Not Started |
