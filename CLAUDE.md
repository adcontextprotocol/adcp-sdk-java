# Repo guide for coding agents

This file is for Claude Code, Cursor, Copilot, and other coding agents. Humans should start with [`README.md`](README.md) and [`CONTRIBUTING.md`](CONTRIBUTING.md).

## What this repo is

The Java SDK for the [AdCP protocol](https://adcontextprotocol.org). Targets v1.0 GA at full L0–L3 parity with `@adcp/sdk` (TypeScript) and `adcp` (Python).

Status: harness phase. Eight Gradle modules wired and green; most surfaces are placeholder `package-info.java` files waiting for track owners.

## Authoritative documents (read these before doing anything non-trivial)

In order of precedence:

1. **[`ROADMAP.md`](ROADMAP.md) §Confirmed decisions** — 21 numbered decisions (D1–D21) that lock in architecture, build, conventions. **These override the RFC where they differ.**
2. **[`docs/rfc/java-sdk-rfc.md`](docs/rfc/java-sdk-rfc.md)** — the merged RFC.
3. **[`ROADMAP.md`](ROADMAP.md) §Tracks** — what each module is supposed to contain, in track-claim form.
4. **[`ROADMAP.md`](ROADMAP.md) §7.x deltas** — what changed in the TS SDK between the RFC's 6.x baseline and the current 7.2.0.

If a task contradicts a confirmed decision, **stop and ask** before coding around it.

## Build commands

JDK 21 required. The Gradle wrapper is committed.

```bash
./gradlew build           # full build, all 8 modules
./gradlew test            # tests only
./gradlew :adcp-cli:run   # run CLI placeholder
./gradlew clean           # nuke build/
```

CI runs `./gradlew build` on every PR via `.github/workflows/ci.yml`.

## Module layout

| Module | Java package | Role |
|---|---|---|
| `adcp` | `org.adcontextprotocol.adcp` | Main library — caller, generated types, schema bundle |
| `adcp-server` | `org.adcontextprotocol.adcp.server` | Agent-side: signing, idempotency, async tasks, webhooks |
| `adcp-testing` | `org.adcontextprotocol.adcp.testing` | Storyboard runner, conformance harness, JUnit 5 fixtures |
| `adcp-spring-boot-starter` | `org.adcontextprotocol.adcp.springboot` | Spring Boot 3.x autoconfig (D7: jakarta only) |
| `adcp-cli` | `org.adcontextprotocol.adcp.cli` | Runnable jar |
| `adcp-reactor` | `org.adcontextprotocol.adcp.reactor` | Project Reactor bridge |
| `adcp-mutiny` | `org.adcontextprotocol.adcp.mutiny` | SmallRye Mutiny bridge |
| `adcp-kotlin` | `org.adcontextprotocol.adcp.kotlin` | Kotlin coroutine + DSL extensions |

Build infrastructure:
- `gradle/libs.versions.toml` — every external dep version. Bump versions here, never inline.
- `build-logic/` — convention plugins shared across modules.

## Conventions to follow

- **Java 21 records + sealed types** wherever they fit. No `Optional<T>` returns — use `@Nullable T` (JSpecify) instead.
- **`*Request` builds, `*Response` doesn't.** This naming invariant prevents IDE auto-complete from suggesting `.builder()` on response types.
- **SLF4J for logging.** Not `java.util.logging`.
- **Conventional Commits** for commit messages. Types: `feat fix docs style refactor perf test build ci chore revert`.
- **Changesets** for adopter-visible changes (`npx changeset`).

## Don't

- Don't add `*Async` mirror methods. D2 (JDK 21 only) dropped them — virtual threads make sync API scale natively.
- Don't add Spring Boot 2.7 / `javax` compat (D7).
- Don't add Bouncy Castle to core (D2/D9 — JDK 21 has Ed25519 natively).
- Don't hand-edit files under `build/generated/` — they regenerate.
- Don't add `Optional<T>` returns — use `@Nullable T`.
- Don't create `docs/adr/` — design decisions land in `ROADMAP.md` rows or `specs/<topic>.md` per D16.

## Codegen

The SDK generates Java types from JSON Schemas at `https://adcontextprotocol.org/protocol/{version}.tgz`. The generator is on the [`codegen` track](ROADMAP.md#track-2--l0-types--codegen) — not yet written. When it is, generator output lands in `adcp/build/generated/` and is **not** checked in.

## Testing

JUnit 5. Conventional test layout (`src/test/java/...`). Tests run with `./gradlew test`. Storyboard CI lives in `adcp-testing` and hits the `@adcp/sdk/mock-server` sidecar in CI (D8).

## Picking up work

The contributor onboarding path is in [`CONTRIBUTING.md`](CONTRIBUTING.md): claim a track via issue, work on a feature branch, PR to `main`. The 14 tracks live in [`ROADMAP.md`](ROADMAP.md#tracks).
