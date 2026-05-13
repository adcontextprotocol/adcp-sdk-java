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
| D8 | Mock-server CI deployment | **Sidecar via `npx adcp mock-server`.** GitHub Actions Node step installs a pinned `@adcp/sdk` version, backgrounds one mock-server per specialism on a port range, Java tests hit `localhost`. The pinned `@adcp/sdk` version is the conformance oracle — bumping it is a deliberate PR. Promote to a published Docker image if multi-specialism orchestration becomes unwieldy. | Specifies D5's deployment |
| D9 | MCP Java SDK | **`io.modelcontextprotocol.sdk:mcp` pinned `1.1.2`** at the core. Used by `adcp` (caller) and `adcp-server` (agent). The Spring AI MCP SDK was donated to the `modelcontextprotocol` org in Feb 2025 and rebranded as the official Java SDK; current `spring-ai-mcp-*` artifacts are now thin Spring Boot wrappers on top of `io.modelcontextprotocol.sdk` — no parallel implementation. **License: MIT** (compatible, flagged for foundation position). Two harness Week 1 prototype questions left open: (a) whether `mcp-core`'s servlet-based streamable-HTTP server transport is usable without pulling Jetty/Tomcat; (b) Jackson 2 vs. 3 module split — confirm `mcp-json-jackson2` is feature-equivalent. | Resolves RFC Open Question 2 |
| D10 | A2A pre-1.0 type strategy | **Keep A2A types in-tree until `a2aproject/a2a-java` cuts a stable ≥ 1.0.0 release**, then migrate to the upstream client in one shot and deprecate the in-tree fallback in the next minor. As of the latest check, `a2a-java` is at `1.0.0.Beta1` (Apr 2026) — package layout still churning, so we don't hard-depend on it yet. | RFC default for Open Question 3 |
| D11 | `TransitionGuard` narrowing protection | **Guards declare which spec edges they touch.** Conformance harness fails if a sandbox account's guards narrow any edge the storyboards exercise. Guards run after the spec edge check and can never relax a spec edge. | Resolves RFC Open Question 7 |
| D12 | Spring Security integration depth | **Recipes-only at v1.0.** No separate `adcp-spring-boot-starter-security` artifact. Auth models vary too much to pre-bake; recipes age better than autoconfig. Revisit if v0.3 design-partner feedback demands it. | RFC default for Open Question 5 |
| D13 | Reactor + Mutiny adapters | **At GA, not fast-follow.** `adcp-reactor` and `adcp-mutiny` both ship in v1.0. WebFlux shops left to wrap the sync API would own that complexity forever and we'd lose the canonical surface. | Confirms RFC §Async model |
| D14 | Kotlin co-release | **At v1.0, thin extension artifact** (`adcp-kotlin`). Coroutine `suspend fun` wrappers + DSL builders. Not a parallel SDK. Defer and Kotlin shops fork. | Confirms RFC §Kotlin positioning |
| D15 | Spec-rev tracking cadence | **≤ 2 weeks from AdCP spec rev to a Java SDK release that consumes it.** Same SLO TS and Python hold to. Slower than 2 weeks and JVM teams stop trusting the parity claim. | Specifies RFC §What kills adoption (item 1) |
| D16 | Design-decision filing convention | **Match AdCP convention.** Longer-form design / decision docs live in `specs/<topic>.md` (the same place the Java SDK RFC itself lives in the spec repo). The Confirmed-decisions table in this ROADMAP is the at-a-glance index; a decision spins up its own `specs/` doc only when the table row isn't enough. No `docs/adr/`, no MADR template, no per-decision file by default. | (No RFC §; sets a repo convention) |
| D17 | Branching model | **Trunk-based.** Short-lived feature branches → PR to `main`. Semver tags from `main`. No long-lived release branches. | (Sets a repo convention; matches TS SDK) |
| D18 | Commits + changelog | **Conventional Commits enforced via commitlint; [Changesets](https://github.com/changesets/changesets) for the CHANGELOG.** Matches the TS SDK's workflow shape (same `.changeset/` directory pattern) so humans and tools read both SDKs' release notes the same way. | (Sets a repo convention; matches TS SDK) |
| D19 | Contributor IPR | **Replicate the AAO IPR Bot pattern** used by adcp / adcp-client / adcp-client-python / adcp-go. Contributors agree by commenting `I have read the IPR Policy` on their first PR; the AAO IPR Bot (a GitHub App) enforces via a required status check. Harness Week 1 actions: (1) foundation admin adds `adcontextprotocol/adcp-sdk-java` to the App's installation scope and the `IPR_APP_ID` / `IPR_APP_PRIVATE_KEY` org-secret scope; (2) add the ~15-line caller workflow at `.github/workflows/ipr.yml` invoking `adcontextprotocol/adcp/.github/workflows/ipr-check-callable.yml@main`; (3) `CONTRIBUTING.md` mirrors the adcp wording and links to `IPR_POLICY.md` in the spec repo. **No DCO. No CLA.** | (Matches AAO standard) |
| D20 | Sonatype OSSRH namespace claim | **Claim `org.adcontextprotocol` via DNS TXT verification.** Maven Central confirms the namespace is unclaimed (zero artifacts today). Requires the foundation to add one `TXT` record to `adcontextprotocol.org` proving control. Sonatype ticket + DNS record both kicked off harness Week 1; first publish waits for v0.3 (per D6). | Specifies D6 |
| D21 | Branch protection on `main` | **Required: 1 code-owner approving review, required CI checks (`build`, `test`, `storyboard`, `IPR Policy / Signature`), no force-push, no direct push, no admin bypass.** Dependabot patch PRs auto-merge after green CI. Two-reviewer gate doesn't add safety with a founder pair; revisit when contributor base grows. | (Sets a repo convention) |

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
| Prototype the two open MCP-SDK questions on `io.modelcontextprotocol.sdk:mcp:1.1.2` (per D9): can `mcp-core`'s servlet streamable-HTTP server transport run without Jetty/Tomcat? Is `mcp-json-jackson2` feature-equivalent to the Jackson 3 variant? | D9 picked the SDK; these two are the only unresolved bits before [`transport`](#track-3--l0-transport-mcp--a2a) opens for claim. | [`transport`](#track-3--l0-transport-mcp--a2a) |
| Sonatype OSSRH namespace claim for `org.adcontextprotocol` + foundation GPG key + key-server publication | Slow-path ticket (1–5 business days); start Week 1 even though first publish waits for v0.3 (per D6). Don't block v0.3 on a stalled OSSRH ticket. | [`infra`](#track-1--build-repo-release-infra) |
| SSRF-safe `HttpClient` wrapper skeleton (DNS pin, address-guards, redirect:manual, body cap) | Baseline 7.x security posture. JDK `HttpClient` doesn't pin natively; this needs a design doc + skeleton before contributors touch outbound HTTP. | [`transport`](#track-3--l0-transport-mcp--a2a) |
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
| v0.1 alpha | M+2 | L0 surface compiles, storyboards green against reference mock-server in CI. Local Gradle artifacts only (per D6 — first Maven Central publish at v0.3). |
| v0.2 alpha | M+4 | L1: RFC 9421 signing/verification, AWS+GCP KMS providers (lazy-init, per-`adcp_use`), webhook signing |
| v0.3 alpha | M+6 | L2 + partial L3: account store, idempotency, async tasks, Spring Boot starter alpha. **First Maven Central publish** (per D6). |
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

- **MCP:** depend on `io.modelcontextprotocol.sdk:mcp` pinned `1.1.2` (per D9). Used by both `adcp` (caller) and `adcp-server` (agent). Plan a deliberate 2.x migration PR ~6 months out (the 2.0 line removes sealed interfaces from message types, replaces `JsonSchema` with `Map`, flips the tool-input-validation default, removes server-transport builder methods). License is MIT — flagged for foundation position. Two open prototype questions land harness Week 1: whether the servlet-based streamable-HTTP server transport works without pulling Jetty/Tomcat, and whether `mcp-json-jackson2` is feature-equivalent to the Jackson 3 module.
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
11. **Design partner outreach.** Anchor candidates by audience segment: one publisher running Spring Boot, one SSP, one broadcaster middleware team. Specific shops TBD.
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
| Funding / staffing confirmed | ⏳ Decision pending |
| Tracks claimed | 0 / 14 |
| Pre-contributor harness | Not Started |
| v0.1 alpha | Not Started |
