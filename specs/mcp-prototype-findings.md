# MCP SDK 1.1.2 — harness prototype findings

**Status:** Closes D9 R1 + R2
**Tracks:** [`transport`](../ROADMAP.md#track-3--l0-transport-mcp--a2a)
**Decisions referenced:** D9

## What we needed to answer

D9 picked `io.modelcontextprotocol.sdk:mcp:1.1.2` and flagged two open questions for harness Week 1:

- **R1**: Can `mcp-core`'s servlet-based streamable-HTTP server transport run without Jetty/Tomcat?
- **R2**: Is `mcp-json-jackson2` feature-equivalent to the Jackson 3 module?

Both are answered. The transport story is cleaner than the agent's initial research suggested.

## R1 — HTTP server transport without Jetty/Tomcat

**Yes.** `mcp-core` 1.1.2 ships three framework-neutral server transport providers directly in the JAR:

| Class | Wire shape |
|---|---|
| `io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider` | The current MCP streamable-HTTP spec |
| `io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider` | SSE (deprecated by the spec but still useful for older clients) |
| `io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport` | Stateless variant for short-lived per-request connections |
| `io.modelcontextprotocol.server.transport.StdioServerTransportProvider` | stdio transport (no HTTP at all) |

These compile against `jakarta.servlet.servlet-api` 6.x. They **don't** depend on Jetty, Tomcat, Undertow, or anything Spring. The adopter brings their own Servlet container at runtime — embedded Jetty, Spring Boot's embedded Tomcat, Undertow inside Quarkus, etc.

The `mcp-spring-webmvc` and `mcp-spring-webflux` artifacts exist for Spring-specific integration but are **optional**. We don't need them.

**Practical consequence:** `adcp-server` declares `jakarta.servlet-api` as `compileOnly` (already the case). Adopters who use the embedded Spring Boot starter get a servlet container for free; adopters who run plain JVM bring their own. No transitive heaviness in the core dep tree.

### Caveat — `server-servlet` is a 0.x line

There's a separate artifact `io.modelcontextprotocol.sdk:server-servlet` that goes up to 0.18.2 and never made the jump to 1.x. **Don't use it.** The 1.x line folded everything into `mcp-core` itself. Adopters who pinned the 0.x line and depended on `server-servlet` separately will need to migrate.

## R2 — Jackson 2 vs. Jackson 3 module parity

**Both modules are at 1.1.2 with identical version cadence.** Neither lags.

- `io.modelcontextprotocol.sdk:mcp-json-jackson3` — the **default** brought in by the `mcp` bundle artifact. Targets the Jackson 3 line (which is still pre-release as of this writing).
- `io.modelcontextprotocol.sdk:mcp-json-jackson2` — explicit opt-in. Targets the Jackson 2 line.

Both are thin bindings on top of the same `mcp-json` interface; they expose the same surface. Choosing one is a matter of which Jackson tree the consumer's other dependencies use.

**We pick `mcp-json-jackson2`.** RFC §JSON pins `jackson-databind >= 2.15` (the records-support floor); the rest of the SDK is on the Jackson 2 tree. Pulling `mcp-json-jackson3` would create a dual-Jackson dep graph for adopters, which is exactly the class of problem the RFC's Jackson pin exists to prevent.

### Build wiring

```kotlin
// adcp-server/build.gradle.kts
dependencies {
    api(libs.mcp.core)            // ← framework-neutral substrate
    api(libs.mcp.json.jackson2)   // ← explicit Jackson 2 binding
    compileOnly(libs.jakarta.servlet.api)
}
```

We **don't** use `io.modelcontextprotocol.sdk:mcp` (the bundle) because it pulls `mcp-json-jackson3` transitively.

## Surface of `mcp-core` 1.1.2

For reference, the relevant top-level packages:

| Package | Role |
|---|---|
| `io.modelcontextprotocol.spec` | Protocol-spec types: `McpTransport`, `McpServerTransportProvider`, `McpStreamableServerTransport`, message envelopes |
| `io.modelcontextprotocol.server` | Server-side: handler registration, capability negotiation, session lifecycle |
| `io.modelcontextprotocol.server.transport` | Concrete server transports (the four listed above) |
| `io.modelcontextprotocol.client` | Client-side: protocol client, session, capability handshake |
| `io.modelcontextprotocol.client.transport` | Concrete client transports: `HttpClientStreamableHttpTransport`, `HttpClientSseClientTransport`, `StdioClientTransport` |
| `io.modelcontextprotocol.auth` | Auth helpers (OAuth metadata, bearer) |
| `io.modelcontextprotocol.session` | Session management |

301 classes total. Everything we need for the AdCP server-side surface lives here.

## 2.0.0 migration (deliberate PR ~6 months out)

The 2.0.0-M2 milestone is on Maven Central. Known breaks:

- Sealed interfaces removed from message types
- `JsonSchema` typed parameter replaced with `Map<String, Object>`
- Tool input validation default flipped
- Server transport builder methods removed (constructors-only)

We track on `1.1.2` for v0.1–v0.3. Migration to 2.x is a deliberate PR when 2.0 GAs (the M-line is still in flight). See D9's note in ROADMAP.

## Smoke test in the repo

`adcp-server/src/test/.../McpDependencyImportsTest.java` imports the four servlet transport providers and asserts each class loads. This is the harness-level "the deps resolve" gate — full integration tests for the transport land on the `transport` track.
