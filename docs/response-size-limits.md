# Response size limits

Every response an AdCP agent sends you is capped. The default is **4 MiB**, it is on without any
configuration, and it is enforced while the body streams in — an oversized response is aborted
mid-read rather than buffered and then rejected.

Agent responses are untrusted input. Without the cap, one agent answering `get_products` with a
17.8 MB payload is enough to push a grader past 10 GB of memory and take down everything else
running in the same process. See [`specs/response-size-limits.md`](../specs/response-size-limits.md)
for the design, and [adcp#8190](https://github.com/adcontextprotocol/adcp/issues/8190) for the
cross-SDK contract.

## What is capped

MCP `callTool`, `tools/list` and discovery; A2A `message/send`; SSE streams on both; agent-card
fetches. Discovery probes — transport detection and `WWW-Authenticate` challenges, which read only
the status line and headers — keep a separate, smaller 4 KiB budget.

## Raising the limit

There is no opt-out. Every setter rejects a non-positive value: an agent that legitimately publishes
a very large catalogue gets a bigger number, not an uncapped read.

```java
// Per client — applies to every agent this client talks to.
AdcpClient client = AdcpClient.builder()
        .agent(agent)
        .maxResponseBytes(32L * 1024 * 1024)
        .build();

// Per agent — for the one seller with the enormous catalogue.
AgentConfig agent = AgentConfig.builder()
        .id("big-catalogue-seller")
        .agentUri(URI.create("https://agent.example.com"))
        .maxResponseBytes(32L * 1024 * 1024)
        .build();

// Per call — for the one call that needs it.
client.callTool("get_products", args, GetProductsResponse.class,
        CallToolOptions.builder().maxResponseBytes(32L * 1024 * 1024).build());
```

Resolution order is **call → agent → client**. The cap is baked into the transport connection, so
overriding it per call opens a separate pooled connection; prefer setting it on the `AgentConfig`
if an agent routinely needs more.

## Handling the error

An over-cap response throws `ResponseTooLargeError` (`code() == "RESPONSE_TOO_LARGE"`), carrying
`limit()`, `bytesRead()` and `url()`. Nothing is truncated — you never get back a half-read payload.

The cap fires inside a JDK HTTP body subscriber, so by the time it reaches you the JDK or the
MCP/A2A transport may have wrapped it. Branch on it with `findIn`:

```java
try {
    return client.callTool("get_products", args, GetProductsResponse.class);
} catch (RuntimeException e) {
    ResponseTooLargeError tooLarge = ResponseTooLargeError.findIn(e);
    if (tooLarge != null) {
        log.warn("{} answered with more than {} bytes — skipping",
                agent.id(), tooLarge.limit());
        return null;
    }
    throw e;
}
```

`ResponseTooLargeError` is not retried by the SDK's evict-and-retry path: re-running the call would
just re-download the same oversized payload.

## Upgrading

This is a behaviour change. Before, the only limit was a 10 MB character check that ran *after* the
response had been fully buffered and parsed; now the effective limit is 4 MiB and it stops the read.
Two things to look at when you upgrade:

- **Large catalogues.** If a call that used to return 6 MB now throws, raise the cap for that agent.
  Start with the per-agent setter — it keeps the tighter default for everyone else.
- **Agent cards over 4 KiB.** Card fetches used to run on the 4 KiB probe budget, and a larger card
  was silently truncated and replaced with a synthetic fallback. They now run on the 4 MiB call cap,
  so a card with a realistic number of skills is read whole for the first time. If you were relying
  on the fallback card's shape, you will now see the agent's real card.

## SSE

Streaming responses get no extra budget. Each SSE event is capped individually, and for anything
that answers a single call the running total across events is capped too — so a peer cannot evade
the limit by replying with `Content-Type: text/event-stream` or by drip-feeding small events.

Note that on a capped call, progress and status events count against the same budget as the final
payload — a long-running task that emits many status updates can reach the limit without any single
message being large.

The one exception is the long-lived MCP SSE session channel: a `GET` that stays open for the life of
the connection and carries every server-to-client message. Capping its running total at one call's
worth would kill a healthy long session after enough legitimate traffic, so it gets a cumulative
budget 256× larger, with the per-event cap unchanged. Per-event is the cap that bounds memory there
— the transport aggregates one event at a time and discards the bytes in between.

## What the cap does not do

It bounds what one peer can make you hold for one response. It is not a global memory budget: wire
bytes become a multiple of themselves once parsed into objects, and a batch job talking to many
agents multiplies that again. If you are fanning out — the shape that got OOM-killed in the first
place — bound your concurrency as well.
