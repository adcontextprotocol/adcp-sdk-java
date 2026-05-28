# Agent surface emission

**Status:** Design spec for D23 codegen/tooling parity
**Tracks:** [`codegen`](../ROADMAP.md#track-2--l0-types--codegen), [`transport`](../ROADMAP.md#track-3--l0-transport-mcp--a2a), [`testing`](../ROADMAP.md#track-9--testing--conformance)
**Decisions referenced:** D9, D23

## Why this exists

D23 pins Java to the signed AdCP bundle for schema/API truth. That affects more than Java records and validators: generated MCP tools and A2A agent metadata must expose the same semantics that TS/Python expose to clients, registries, and conformance storyboards.

## Emission rules

- **MCP tool descriptions:** generated descriptions consume the protocol bundle's tool and field text. The Java SDK may tighten grammar for JavaDoc, but must not invent semantics that are absent from the bundle.
- **MCP input annotations:** universal request fields such as `idempotency_key` are represented on every tool input. When MCP supports idempotency annotations, generated tools set `idempotentHint: true` for tools whose AdCP request surface accepts idempotency.
- **A2A skill IDs:** generated A2A skill IDs follow a cross-SDK stable convention so Java and TS agents dedupe cleanly in registries. The convention is owned by the protocol bundle or a follow-up cross-SDK spec, not by local Java names.
- **Java names are not wire names:** Java method/class names may follow Java idiom, but emitted MCP tool names, A2A skill IDs, and JSON schema names stay protocol-stable.

## Test contract

The v0.1 conformance runner should snapshot generated MCP tool descriptors and A2A skill metadata from the target bundle. Snapshot diffs require either a bundle bump or an explicit roadmap/spec update.
