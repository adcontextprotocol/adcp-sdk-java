# Java SDK Implementation Plan

Working plan to take the [Java SDK RFC](docs/rfc/java-sdk-rfc.md) from zero to v1.0 GA at full L0–L3 parity with `@adcp/sdk` (TS) and `adcp` (Python).

This is a living document. Status updates land here; design decisions land in the RFC or in a follow-up RFC.

## Confirmed decisions

Decisions made post-RFC that supersede or refine the merged text. Each will land as a numbered ADR under `docs/adr/` during the harness phase.

| # | Decision | Resolution | Supersedes RFC |
|---|---|---|---|
| D1 | Repo home | Work in `adcontextprotocol/adcp-sdk-java` on feature branches → PR to `main`. | RFC §Build, distribution, governance (named the repo, didn't specify cadence) |
| D2 | Java baseline | **JDK 21 only.** Drops the 17 floor. Consequences: no `*Async` mirror surface (virtual threads make sync API scale natively), no platform-thread fallback executor in `WebhookEmitter`, `ScopedValue` directly for `UpstreamRecorder` per-principal scope, no Spring Boot 2.7 long-tail support. | RFC §Architecture / Java baseline (was "Java 17 LTS"); RFC §Async model (drops the 12-method `*Async` mirror) |
| D3 | Maven coordinates | Group `org.adcontextprotocol`. Artifacts: `adcp` (main), `adcp-server`, `adcp-testing`, `adcp-spring-boot-starter`, `adcp-cli`, `adcp-reactor`, `adcp-mutiny`, `adcp-kotlin`. Base Java package `org.adcontextprotocol.adcp.*`. Sub-packages by surface (e.g. `.task`, `.server`, `.signing`, `.testing`). | RFC §Reference (named artifacts, didn't pin group / base package) |
| D4 | Protocol tarball Sigstore | Confirmed signed. Harness schema-fetcher runs `cosign verify-blob` per RFC, no checksum-only fallback path. | Confirms RFC §Schema-bundle consumption assumption |
| D5 | Reference mock-server | The `@adcp/sdk/mock-server` package — same mock-server TS uses. Storyboard CI either runs it as a Node-side-car service container or against a hosted instance. Decide deployment shape during harness Week 1. | Specifies RFC §`comply_test_controller` "shared reference mock-server" |

## Parity baseline (as of 2026-05-13)

The RFC tracks `@adcp/sdk` 6.x. Current state of the world:

| SDK | Version | Notes |
|---|---|---|
| `@adcp/sdk` (TS) | 7.2.0 | RFC was authored against 6.x; 7.x added `upstream-recorder` and other surface |
| `adcp` (Python) | 4.x (beta) | Subpackages: `compat`, `decisioning`, `migrate`, `protocols`, `schemas`, `server`, `signing`, `testing`, `types`, `utils`, `validation` |
| `adcp-go` | v1.x (dev) | Reference for a third-language take |

**Action: T0** — re-verify the RFC's parity table against `@adcp/sdk` 7.2.0 exports before v0.1 cut. The 5-artifact target still holds; the 7.x additions collapse into existing artifacts. Specifics below.

### 7.x deltas since the RFC was written

Read from the TS SDK CHANGELOG. Each delta lists the Java track it folds into. None invalidate the RFC; all bump scope inside an existing track.

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

### 7.x impact on the milestone calendar

The 7.x deltas don't move milestones, but they do tighten the v0.1 gate:

- v0.1 release gate **adds**: SSRF baseline (DNS pin, address-guards, redirect:manual, body cap) on all discovery probes. `WWW-Authenticate`-aware error envelope. HTTP Basic config support. The `storyboards_missing_tools` / `storyboards_not_applicable` split in the storyboard runner output.
- v0.2 gate **adds**: nothing new from 7.x — the L1 signing surface didn't grow.
- v0.3 gate **adds**: `IDEMPOTENCY_IN_FLIGHT` wire code with claim-age-derived `retry_after` cap. `resolveAgentProperties` / `validateAdAgents` / `MANAGERDOMAIN` fallback.
- v0.4 gate **adds**: `upstream-recorder` SPI, `query_upstream_traffic` controller scenario, `parallel_dispatch` storyboard step, full `RunnerNotice` taxonomy.

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
| MCP SDK decision resolved (Spring AI vs. official `io.modelcontextprotocol`) + recorded in an ADR | Blocks [`transport`](#track-3--l0-transport-mcp--a2a). Drift between releases is worse than picking the less-mature option and migrating. | [`transport`](#track-3--l0-transport-mcp--a2a) |
| SSRF-safe `HttpClient` wrapper skeleton (DNS pin, address-guards, redirect:manual, body cap) | Baseline 7.x security posture. JDK `HttpClient` doesn't pin natively; this needs a design doc + skeleton before contributors touch outbound HTTP. | [`transport`](#track-3--l0-transport-mcp--a2a) |
| Storyboard CI gate shell: GitHub Actions on JDK 21, runs the runner against the `@adcp/sdk/mock-server`, even if the runner currently asserts only "we reached the server" | The v0.1 release gate is "storyboards green in CI." Standing it up empty and having it pass keeps contributors honest as L0 fills in — every PR is measured against the gate. | [`infra`](#track-1--build-repo-release-infra) + [`testing`](#track-9--testing--conformance) |
| Repo conventions: `CONTRIBUTING.md` (track-claim flow), `.github/ISSUE_TEMPLATE/track-claim.md`, PR template, `CLAUDE.md` for agent contributors | The track-claim issue template is the actual contributor onboarding doc | [`docs`](#track-14--docs-migration-troubleshooting) |
| ADR directory + first three ADRs (Java baseline, async model, MCP SDK choice) | RFC decisions captured in the format contributors expect to amend | [`docs`](#track-14--docs-migration-troubleshooting) |

### Explicitly **not** in the harness

These look tempting to "get started on" but pre-building them locks in design that should be a track owner's call:

- L1 RFC 9421 signing — too much spec surface; lives in [`signing`](#track-4--l1-signing).
- Full type generation — [`codegen`](#track-2--l0-types--codegen)'s job once MVP is proven.
- Spring Boot starter — downstream, blocks on L1/L2/L3 surface.
- Account store / idempotency / webhook code — design-heavy; needs the track owner's voice.
- Lifecycle YAML coordination — depends on TS/Python maintainer buy-in (RFC Decision 6).

### Ordering

- **Week 1 (founder pair):** Gradle skeleton, schema fetcher, codegen MVP, repo conventions, ADR directory, CI shell.
- **Week 2 (founder pair + advisors):** MCP SDK pick + ADR. SSRF wrapper skeleton + design doc. First storyboard CI run green-against-empty. Open the first 3–4 track-claim issues publicly.
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
| v0.1 alpha | M+2 | L0 surface compiles, storyboards green against reference mock-server in CI, Maven Central alpha published |
| v0.2 alpha | M+4 | L1: RFC 9421 signing/verification, AWS+GCP KMS providers (lazy-init, per-`adcp_use`), webhook signing |
| v0.3 alpha | M+6 | L2 + partial L3: account store, idempotency, async tasks, Spring Boot starter alpha |
| v0.4 beta | M+9 | Full L3: transition validators, webhook emission, `comply_test_controller`, A2A transport |
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
- Maven Central publish via Sonatype OSSRH, GPG-signed; Sigstore migration tracked as a follow-up.
- GitHub Actions on JDK 21, run storyboard CI against `@adcp/sdk/mock-server` (the v0.1 gate; storyboard runner from [`testing`](#track-9--testing--conformance) plugs in).
- JavaDoc + sources jars on every release.

**Out of scope:** GraalVM native-image (post-v1.0), JPMS modules (opt-in only).

**Depends on:** nothing. This track unblocks everything else and should land in week 1.

**Milestone targets:** v0.1 needs Gradle multi-module + Maven Central alpha publish + CI matrix. Sigstore signing migration can land any time before v1.0.

---

### Track 2 — L0 types & codegen

**ID:** `codegen` | **Owner:** TBD | **Size:** 2.0 person-months

**Scope:**

- Custom codegen on Eclipse JDT or JavaPoet, emitting Java records for value/response types and builder-records for request types.
- Generator invariant: `*Request` types always have builders; `*Response` types are records and never do (RFC §Type generation).
- Polymorphic envelope handling (Jackson `@JsonTypeInfo` / `@JsonSubTypes`).
- `x-adcp-*` annotation post-processors mirroring `scripts/generate-types.ts` in `adcp-client`.
- Version pinning support (`adcp-v2-5` co-existence namespace).
- JSpecify `@Nullable` annotations on every public type. No `Optional<T>` returns.
- Schema validator wrapper around `com.networknt:json-schema-validator`.
- Schema-bundle accessor (runtime, resources jar; build-time loader lives in [`infra`](#track-1--build-repo-release-infra)).

**Out of scope:** Kotlin source generation (handled by [`kotlin`](#track-11--kotlin-extensions)).

**Depends on:** `infra` (codegen Gradle task hookpoint).

**Milestone targets:** v0.1 needs full generated type coverage for the L0 surface and validator wired into transport.

---

### Track 3 — L0 transport: MCP + A2A

**ID:** `transport` | **Owner:** TBD | **Size:** 1.5 person-months

**Scope:**

- **MCP:** pick between Spring AI's MCP SDK and the official `io.modelcontextprotocol` (Open Question 2 in the RFC). Decide before v0.1 cut; drift between releases is worse than picking the less-mature one and migrating later. Wrap the chosen SDK with the AdCP transport surface.
- **A2A pre-1.0:** minimal SSE consumer + JSON-RPC framer in `adcp-server`. Default: keep types in-tree until `a2a-java` cuts its first stable release (≥ 1.0.0), then migrate in one shot (RFC Open Question 3).
- **A2A post-1.0:** swap transport to `a2aproject/a2a-java`; deprecate the in-tree fallback in the next minor.
- HTTP transport on `java.net.http.HttpClient`. No third-party HTTP client in the core.
- Jackson `ObjectMapper` with `StreamReadConstraints` / `StreamWriteConstraints` widened to AdCP-shaped defaults (RFC §JSON).
- **No `*Async` mirror methods.** With JDK 21 as baseline, virtual threads make the sync API scale natively; the RFC's 12-method `*Async` mirror surface is dropped (see [Confirmed decisions](#confirmed-decisions)). Adopters who explicitly want `CompletableFuture` wrap individual calls themselves.

**Out of scope:** OkHttp / Apache HttpClient 5 adapters (post-v1.0 on demand).

**Depends on:** `codegen` for the request/response types.

**Milestone targets:** v0.1 needs MCP transport. v0.4 swaps in upstream `a2a-java` if its 1.0 has cut by then; otherwise the in-tree fallback ships at v1.0 with the swap-trigger documented.

---

### Track 4 — L1 signing

**ID:** `signing` | **Owner:** TBD | **Size:** 1.5 person-months

**Scope:**

- Hand-rolled RFC 9421 canonicalizer (it's small and spec-tight; `org.tomitribe:http-signatures` is the wrong spec). Verifier test harness mirrors the TS one.
- `SigningProvider` SPI via `META-INF/services/`. API shape: `SigningProvider.forUse(AdcpUse.WEBHOOK)` returns a distinct provider from `.forUse(AdcpUse.REQUEST)` — receivers enforce purpose at JWK `adcp_use`.
- In-process provider via JCA Ed25519 / ECDSA. **No Bouncy Castle in core** — JDK 21 has Ed25519 natively.
- AWS KMS provider via `software.amazon.awssdk:kms`. Lazy-init.
- GCP KMS provider via `com.google.cloud:google-cloud-kms`. Lazy-init.
- Optional `adcp-signing-bouncycastle` artifact for FIPS environments.
- Outbound webhook signing wired into [`async-l3`](#track-6--l3-idempotency-async-tasks-webhooks).
- Pre-deploy KMS probe as a separate CLI command, not part of boot critical path.

**Out of scope:** Azure KMS (post-v1.0 on demand). Hardware HSM integration beyond JCA (post-v1.0).

**Depends on:** `transport` (signing wraps HTTP-level requests).

**Milestone targets:** v0.2 ships RFC 9421 + AWS+GCP KMS + webhook outbound signing.

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
- Sandbox/live boundary enforcement at the `AccountStore` (so `comply_test_controller` calls return `COMPLY_NOT_AVAILABLE` on production accounts per spec).
- Agent-card publication helper.

**Out of scope:** Caller-side credential presentation beyond what L0 already covers (folded into [`transport`](#track-3--l0-transport-mcp--a2a)).

**Depends on:** `codegen` for principal / account types.

**Milestone targets:** v0.3 alpha ships full L2.

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

**Out of scope:** Persistence migrations beyond reference schemas (adopter responsibility).

**Depends on:** `codegen`, `signing` (webhook outbound), `multitenant` (sandbox boundary).

**Milestone targets:** Partial in v0.3 (idempotency + async tasks). Full webhook emitter in v0.4.

---

### Track 7 — L3 lifecycle & transitions

**ID:** `lifecycle` | **Owner:** TBD | **Size:** 1.5 person-months (Java-side; coordination cost with TS/Python maintainers is separate)

**Scope:**

- Decide between RFC paths 1 and 2 (RFC §Lifecycle and transition validation). Recommendation in RFC: path 2 (lead the cross-SDK shared YAML lifecycle source). Decision depends on TS + Python maintainer commitment — see [Decisions wanted](#decisions-wanted).
- If path 2: author lifecycle YAMLs in the spec repo for the 7 resources (`MediaBuy`, `Creative`, `Account`, `SISession`, `CatalogItem`, `Proposal`, `Audience`). Wire all three SDKs to consume them.
- Transition validator API takes `(action, from, to)`, not `(from, to)` — `NOT_CANCELLABLE` precedence over `INVALID_STATE` requires the action.
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
- `MockAgent` for callers under test (buyer-side mirror).
- `Personas` port of `/testing/personas`.
- Signing test fixtures (port of `/signing/testing`).

**Out of scope:** Test infrastructure beyond JUnit 5 (TestNG support is adopter's problem if they want it).

**Depends on:** `codegen`, `transport`. The mock-server forwarding adapter is the v0.1 release gate — without it, CI claims conformance it doesn't have.

**Milestone targets:** v0.1 ships the storyboard runner + forwarding adapter. Conformance test surface expands at each subsequent milestone as L1/L2/L3 land.

---

### Track 10 — Spring Boot starter

**ID:** `spring` | **Owner:** TBD | **Size:** 1.0 person-month

**Scope:**

- `adcp-spring-boot-starter` auto-configures: handler, Jackson `ObjectMapper`, signing provider, account store.
- Micrometer `MeterRegistry` integration **if on classpath**. Metric names: `adcp.tool.duration`, `adcp.signing.verify.failures`, etc.
- Actuator `AdcpHealthIndicator` **if on classpath** — reports signing-key reachability + account-store reachability.
- Spring properties for tunables: `adcp.jackson.max-string-length`, etc.
- Spring Security integration as a **documented recipe**, not autoconfig (RFC §Server framework integration). Decision on `adcp-spring-boot-starter-security` deferred to v0.3 feedback.
- **`javax` vs `jakarta` decision** (RFC Open Question 6): default recommendation is floor at Spring Boot 3.x (`jakarta`). Confirm before v0.3 alpha — starter package layout depends on it.

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

In priority order, from the RFC:

1. **Funding / staffing.** A contributed engineer at 50%+ for ~12 months, plus a named WG maintainer with merge rights, plus 2–3 design partners committed v0.1 → v0.4. Without all three, the RFC says decline and revisit at next major.
2. **Design partners.** 2–3 JVM shops with letters of intent to ship on the SDK in 2026.
3. **WG vote** on Java as the fourth officially supported language.
4. **Maintainer.** Named owner with merge rights post-GA.
5. **MCP Java SDK choice.** Spring AI vs. official `io.modelcontextprotocol`. Blocks `transport`.
6. **Cross-SDK lifecycle YAML buy-in.** TS + Python maintainers willing to consume a shared source. Decides `lifecycle` path 1 vs. path 2.
7. **`javax` vs `jakarta` floor.** Decides `spring` artifact layout.
8. **Reactor/Mutiny + Kotlin at GA vs. fast-follow** — RFC's position is "at GA"; WG can cut scope.

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
| Funding / staffing confirmed | ⏳ Decision pending |
| Tracks claimed | 0 / 14 |
| Pre-contributor harness | Not Started |
| v0.1 alpha | Not Started |
