# Response size limits

**Status:** Shipped — closes [#39](https://github.com/adcontextprotocol/adcp-sdk-java/issues/39)
**Tracks:** [`transport`](../ROADMAP.md#track-3--l0-transport-mcp--a2a)
**Decisions referenced:** D2 (JDK 21), D9 (MCP SDK pin), D10 (A2A SDK pin), D24
**Cross-SDK tracking:** [adcontextprotocol/adcp#8190](https://github.com/adcontextprotocol/adcp/issues/8190)

## Why this exists

An AdCP agent's response is untrusted input. A hosted compliance grader was repeatedly OOM-killed
when a seller agent answered a single `get_products` call with a 17.8 MB payload: process memory
went past 10 GB, almost all of it outside the JS heap, and every agent graded in the same batch
starved. With a 4 MiB response cap the same run peaks at 1.6 GB.

A buyer, crawler or grader built on an AdCP SDK should not be memory-DoS-able by one agent, and
that protection must not depend on every adopter remembering to opt in. So the cap is **on by
default**, in every SDK, at the same number.

The Java-specific problem is that the SDK does not own the sockets on the call paths. MCP calls go
through `HttpClientStreamableHttpTransport` / `HttpClientSseClientTransport` and A2A calls through
`JdkA2AHttpClient` — all third-party, none of which exposes a body-size hook. What they *do* accept
is a JDK `java.net.http.HttpClient`. That is the choke point this design uses.

## Defaults

| Scope | Default | Where to change it |
|---|---|---|
| Agent responses (MCP `callTool`, A2A `message/send`, SSE, `tools/list`, agent card) | **4 MiB** (`4 * 1024 * 1024`) | `AdcpClient.Builder.maxResponseBytes(long)` |
| Per agent | inherits the client default | `AgentConfig.Builder.maxResponseBytes(long)` |
| Per call | inherits the agent default | `CallToolOptions.Builder.maxResponseBytes(long)` |
| Discovery probes (transport detection, `WWW-Authenticate` challenge) | 4 KiB | `AdcpHttpClient.Builder.maxResponseBytes(long)` |

Resolution order for an agent response is **call → agent → client**. There is no opt-out: every
setter rejects a non-positive value. An adopter with a genuinely large catalogue raises the number;
nobody turns the cap off.

```java
// Per client
AdcpClient client = AdcpClient.builder()
        .agent(agent)
        .maxResponseBytes(32L * 1024 * 1024)
        .build();

// Per agent
AgentConfig agent = AgentConfig.builder()
        .id("big-catalogue-seller")
        .agentUri(URI.create("https://agent.example.com"))
        .maxResponseBytes(32L * 1024 * 1024)
        .build();

// Per call
client.callTool("get_products", args, GetProductsResponse.class,
        CallToolOptions.builder().maxResponseBytes(32L * 1024 * 1024).build());
```

## The error

An over-cap response throws
[`ResponseTooLargeError`](../adcp/src/main/java/org/adcontextprotocol/adcp/error/ResponseTooLargeError.java)
(`code() == "RESPONSE_TOO_LARGE"`), carrying `limit()`, `bytesRead()` and `url()`. Nothing is ever
truncated on a path whose body is parsed.

The cap fires inside a `java.net.http` body subscriber, so the JDK — and then the MCP/A2A
transports — may wrap it before it reaches the caller. Two things follow:

- Callers who want to branch on it should use `ResponseTooLargeError.findIn(throwable)`, which walks
  the cause and suppressed chains with a depth bound.
- The SDK's own transport code unwraps it (`McpCaller`, `A2aCaller`, `McpConnectionManager`,
  `A2aConnectionManager`) so that `ProtocolClient`'s evict-and-retry path never re-downloads an
  oversized payload as if it were a transient I/O blip.

## How enforcement works

[`ResponseSizeLimitingHttpClient`](../adcp/src/main/java/org/adcontextprotocol/adcp/http/ResponseSizeLimitingHttpClient.java)
decorates a JDK `HttpClient`. `AdcpHttpClient.newHttpClientBuilder(long)` returns a
`HttpClient.Builder` that builds one, so the MCP transports (`clientBuilder(...)`) and
`JdkA2AHttpClient` get a capped client without knowing it. Four properties:

1. **Pre-reject on `Content-Length`.** A declared length over the cap fails before one body byte is
   read.
2. **Count while reading.** Bytes are counted in `Flow.Subscriber.onNext`; the subscription is
   cancelled the instant the cap is crossed. A check after the body is buffered or parsed would not
   prevent the OOM this exists to stop.
3. **SSE per event and per call.** For `Content-Type: text/event-stream`, bytes are counted per
   event (reset at each blank-line delimiter) *and* cumulatively for the whole response when the
   response answers a single call — any non-`GET` request. Answering a POST with
   `text/event-stream` buys a peer no extra budget.
4. **Decompressed bytes.** Outbound requests are rewritten to `Accept-Encoding: identity`,
   replacing any caller-supplied value, so a peer cannot hide a 1 GiB payload behind a small gzip
   stream. (The JDK `HttpClient` never decompresses transparently, so wire bytes *are* the bytes the
   caller holds.)

### The one deliberate asymmetry: long-lived `GET` SSE streams

The MCP SSE session channel is a `GET` that stays open for the life of the connection and carries
every server-to-client message. A cumulative cap there would kill healthy long sessions after
enough legitimate traffic — so that stream gets the **per-event cap only**.

This is the right trade for the threat being defended against. OOM comes from how much is held at
once, and the transports aggregate one SSE event at a time, discarding bytes in between: per-event
is the cap that bounds memory. Cumulative capping is about bounding a *single call*, and a listening
stream is not a call. Every path that *is* a call — MCP StreamableHTTP POST, A2A
`message/send`, A2A streaming POST — is capped cumulatively as well as per event.

### Probes keep their own, smaller cap

`AdcpHttpClient.send` / `get` / `post` are discovery probes: a POST `ping` to detect StreamableHTTP
support, and HEAD/OPTIONS to read a `WWW-Authenticate` challenge. They use only the status line and
headers. Those keep the 4 KiB cap from `specs/ssrf-baseline.md` and stop reading rather than throw,
flagging the result via `AdcpHttpResponse.truncated()` — not silent, and no caller parses the body.

`getForAgentCard` *is* parsed, so it moved to the strict 4 MiB call cap. Before this change it ran
on the 4 KiB probe cap and an agent card larger than 4 KiB — easily reached with a handful of skills
— was silently truncated and swapped for a synthetic fallback card.

## Connection caching

The cap is baked into the transport's `HttpClient` at connect time, so it is part of the connection
cache key: `agentUrl::tokenHash::maxResponseBytes` (MCP) and `agentUri#cacheHash#maxResponseBytes`
(A2A). A call that overrides the cap gets a connection built for that cap rather than silently
reusing one built for another. Varying the cap per call therefore fragments the connection cache —
prefer setting it once on the `AgentConfig`. `A2aConnectionManager` pools at most 8 distinct capped
HTTP clients and builds unpooled ones beyond that, so a caller that varies the cap wildly cannot
grow an unbounded pool.

## Cross-SDK contract

Behaviour that must stay identical across the TypeScript, Python, Go and Java SDKs
([adcp#8190](https://github.com/adcontextprotocol/adcp/issues/8190)):

1. On by default for every agent response on the MCP and A2A call paths, including discovery and
   tool listing. Default 4 MiB. Configurable per client, per agent and per call.
2. Oversized responses fail with a typed, documented error named `ResponseTooLarge…` per language
   convention. Never a silent truncation.
3. Enforced while reading, with a `Content-Length` pre-reject.
4. SSE covered per event and cumulatively per call.
5. Decompressed bytes counted, or identity encoding forced.
