# Contributing to the AdCP Java SDK

Contributions are welcome. Fork, branch, commit, [open a pull request](https://help.github.com/articles/using-pull-requests/) against `main`.

## Intellectual Property Rights

Before contributing, you must agree to the AAO [Intellectual Property Rights Policy](https://github.com/adcontextprotocol/adcp/blob/main/IPR_POLICY.md). When you open a pull request, the **AAO IPR Bot** will comment asking you to confirm by replying:

```
I have read the IPR Policy
```

This is a one-time requirement per contributor across all AAO repositories. The bot writes your signature to the central ledger in [`adcontextprotocol/adcp`](https://github.com/adcontextprotocol/adcp/blob/main/signatures/ipr-signatures.json), so once you've signed for any AAO repo, you're set for this one too.

## What to work on

Read [`ROADMAP.md`](ROADMAP.md) first. It divides v0.1 → v1.0 into 14 claimable tracks. To claim a track, open an issue with the **Claim a track** template:

- Your prior JVM experience (records, sealed types, Jackson, Spring Boot, RFC 9421 signing if relevant).
- Estimated availability per week.
- Which milestone you're committing to first.

A single contributor can claim multiple non-conflicting tracks. High-coupling tracks (`async-l3` + `lifecycle`) benefit from a single owner pair.

Before designing anything new, read the [21 confirmed post-RFC decisions](ROADMAP.md#confirmed-decisions). They override the RFC where they differ.

## Dev setup

Requirements:
- JDK 21 (Temurin recommended)
- The Gradle wrapper (committed) — don't install Gradle separately.

Local build:

```bash
./gradlew build           # compile + test all 8 modules
./gradlew :adcp-cli:run   # run the CLI placeholder
./gradlew test            # tests only
./gradlew check           # tests + lint (alias)
```

CI runs the same command on every PR (`build` workflow, JDK 21 Temurin). The build is **green** if your PR passes.

## Code conventions

- **Java 21 baseline** (D2). Use records, sealed types, pattern matching, virtual threads. No `Optional<T>` returns — use `@Nullable T` (JSpecify) instead.
- **Base package** `org.adcontextprotocol.adcp.*` (D3). Sub-packages by surface: `.task`, `.server`, `.signing`, `.testing`, etc.
- **Naming invariant** (RFC §Type generation): `*Request` types always have builders; `*Response` types are records and never do. This rule is what keeps coding-agent assistance (Claude / Copilot) honest.
- **Logging**: SLF4J only. No `java.util.logging`, no `commons-logging`.
- **Dependencies**: declare every external dep in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Don't pin versions inline in module `build.gradle.kts` files.

## Conventional Commits + Changesets

Commit messages and PR titles must follow [Conventional Commits](https://www.conventionalcommits.org/). Allowed types:

```
feat fix docs style refactor perf test build ci chore revert
```

PRs that change the public surface (anything an adopter would notice) need a changeset. Add one with:

```bash
npx changeset
```

Pick the affected packages (one or more of the eight artifacts), pick a bump level (`patch` / `minor` / `major`), and write a short summary. The `changeset-check` CI job fails if a non-trivial PR is missing a changeset.

For pure refactors, docs, or test-only PRs, no changeset is needed.

## Design decisions

Most design decisions live in [`ROADMAP.md`](ROADMAP.md#confirmed-decisions) as one-line table rows. When a decision needs more space, it gets its own `specs/<topic>.md` doc — matching the AdCP family convention (the Java SDK RFC itself lives at `specs/java-sdk-rfc.md` in the spec repo).

If you propose changing a confirmed decision, open an issue first. PR-as-RFC ends in a stalled merge.

## Schemas and generated code

AdCP wire types are **generated** from the JSON Schemas published at `https://adcontextprotocol.org/protocol/{version}.tgz`. Don't hand-edit generated Java files — the next codegen run will overwrite them. If you need to fix a generated type, the fix lives in the generator (the `codegen` track), not the output.

## Issues and questions

- [Issues](https://github.com/adcontextprotocol/adcp-sdk-java/issues) for bugs and feature requests.
- [adcontextprotocol.org](https://adcontextprotocol.org/) for protocol-level documentation.
- The [AdCP Slack working group](https://adcontextprotocol.slack.com) for design discussion.

## License

This project is licensed under the [Apache License 2.0](LICENSE). See also the AAO [IPR Policy](https://github.com/adcontextprotocol/adcp/blob/main/IPR_POLICY.md).
