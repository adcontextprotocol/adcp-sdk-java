---
"adcp": minor
"adcp-server": minor
"adcp-testing": minor
---

feat(negotiation): add first-class buyer and seller proposal APIs for AdCP 3.2

Introduces the `negotiation` package with sealed outcome models, capability-aware
request builders, terms digest verification (RFC 8785 JCS), response verification
utilities, and server-side handler interface for `refine_proposals`.
