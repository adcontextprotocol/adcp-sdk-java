---
"adcp": minor
---

feat(transport): cap agent response size at 4 MiB by default

Agent responses are untrusted input. Every MCP and A2A call response — tool calls, `tools/list`,
SSE streams and agent-card fetches — is now capped at **4 MiB** by default, and the cap is enforced
while the body streams in: a declared `Content-Length` over the cap is rejected before a byte is
read, and a chunked body is aborted the moment the running count crosses it. SSE is capped per
event as well as cumulatively per call, so a peer cannot buy extra budget by answering with
`Content-Type: text/event-stream`. Outbound requests force `Accept-Encoding: identity` so the
counted bytes are the bytes you hold.

Previously the only limit on the call paths was a 10 MB *character* check that ran after the whole
body had been buffered and parsed — far too late to stop a memory exhaustion. One agent answering
`get_products` with a 17.8 MB payload was enough to OOM-kill a hosted grader at 10+ GB RSS.

**This is a behaviour change.** A call that used to return a 6 MB catalogue now throws
`ResponseTooLargeError` (`code() == "RESPONSE_TOO_LARGE"`). Raise the limit where you need it —
there is no opt-out, only a bigger number:

```java
// per client
AdcpClient.builder().agent(agent).maxResponseBytes(32L * 1024 * 1024).build();

// per agent
AgentConfig.builder().id("big-catalogue").agentUri(uri)
        .maxResponseBytes(32L * 1024 * 1024).build();

// per call
client.callTool("get_products", args, GetProductsResponse.class,
        CallToolOptions.builder().maxResponseBytes(32L * 1024 * 1024).build());
```

The cap is enforced inside a JDK body subscriber and may reach you wrapped, so branch on it with
`ResponseTooLargeError.findIn(throwable)`.

Also in this change:

- `CallToolOptions.maxResponseBytes` is now enforced (it was documented as "reserved, not yet
  enforced"). `CallToolOptions.timeout` remains reserved.
- `AgentConfig` gains a `maxResponseBytes` component, and `AdcpClient.Builder` /
  `AgentConfig.Builder` gain `maxResponseBytes(long)`. Code that calls the `AgentConfig` canonical
  constructor positionally needs one more argument; the builder is unaffected.
- Agent-card fetches moved from the 4 KiB discovery-probe cap to the 4 MiB call cap and now throw
  instead of silently truncating — a card over 4 KiB used to be swapped for a synthetic fallback.
- MCP and A2A connection caches are keyed by the response cap, so a call that overrides it does not
  reuse a connection built for a different one.
- Agent-card fetches get their own read deadline. `HttpRequest.timeout` only covers status line and
  headers, so a peer that sends headers and then drips or stalls could otherwise pin a thread for as
  long as it liked.

Defaults, overrides and the SSE rules are documented in `docs/response-size-limits.md`, with the
design in `specs/response-size-limits.md`.
Cross-SDK contract: https://github.com/adcontextprotocol/adcp/issues/8190
