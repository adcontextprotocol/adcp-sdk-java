# RFC 9421 Canonicalizer Design

**Status:** Implementation complete (Track 4 v0.2)
**Tracks:** [`signing`](../ROADMAP.md#track-4--l1-signing)
**Decisions referenced:** D2 (JDK 21), D22 (SigningContext)

## Why this exists

The AdCP protocol mandates RFC 9421 message signatures for both request signing (buyer → seller) and webhook signing (seller → buyer). The canonicalizer is the single point of truth for constructing and verifying signature bases. A bug in the canonicalizer means every signed message is either rejected or forgeable.

## Design decisions

### Hand-rolled, not org.tomitribe

`org.tomitribe:http-signatures` implements the Cavage IETF draft, not RFC 9421. Key differences:
- Cavage uses `Signature` headers with `keyId` and `algorithm` as direct parameters; RFC 9421 uses `Signature-Input` + `Signature` header pairs with structured parameters including `created`, `expires`, `nonce`, `tag`.
- Cavage doesn't define `content-digest` as a covered component; AdCP requires it for webhooks.
- Cavage doesn't have the `tag` parameter; AdCP uses `tag="adcp/webhook-signing/v1"` and `tag="adcp/request-signing/v1"` to disambiguate profiles.

### Module structure

| Module | Package | Contents |
|--------|---------|----------|
| `adcp` | `org.adcontextprotocol.adcp.signing` | SPI interfaces: `SigningProvider`, `VerificationKeyResolver`, `SigningContext`, `AdcpUse`, `TenantId`, `PrincipalRef`, `Signature`, `VerificationResult`, etc. |
| `adcp-server` | `org.adcontextprotocol.adcp.server.signing` | Canonicalizer, `InProcessSigningProvider`, `InProcessVerificationProvider`, `WebhookSigner`, profile validation |
| `adcp-signing-aws-kms` | `org.adcontextprotocol.adcp.signing.aws` | AWS KMS provider (stub for v0.2) |
| `adcp-signing-gcp-kms` | `org.adcontextprotocol.adcp.signing.gcp` | GCP KMS provider (stub for v0.2) |
| `adcp-signing-bouncycastle` | `org.adcontextprotocol.adcp.signing.bc` | BC FIPS provider (stub for v0.2) |

### Algorithm support

| Algorithm | JCA name | Key type | Status |
|-----------|----------|----------|--------|
| Ed25519 | `EdDSA` | `OKP` with `crv=Ed25519` | Supported (JDK 21 native) |
| ES256 (P-256) | `SHA256withECDSA` | `EC` with `crv=P-256` | Supported (JDK 21 native) |
| ES384 (P-384) | `SHA384withECDSA` | `EC` with `crv=P-384` | Supported (JDK 21 native) |
| RS256 | `SHA256withRSA` | `RSA` | Not in v0.2 scope |

### AdCP profile constraints

The `AdcpSignatureProfile` class enforces:

**Webhook signing (`tag="adcp/webhook-signing/v1"`):**
- Required covered components: `@request-target`, `@authority`, `content-digest`, `x-adcp-timestamp`
- Required signature parameters: `created`, `expires`, `nonce`, `keyid`, `alg`
- Replay window: 300 seconds (5 minutes)
- `content-digest` is REQUIRED (no opt-out)
- `adcp_use` on the verifying JWK MUST be `"webhook-signing"`

**Request signing (`tag="adcp/request-signing/v1"`):**
- Required covered components: `@request-target`, `@authority`, `content-digest`, `x-adcp-timestamp`
- `covers_content_digest` can be `"forbidden"` for GET requests without a body
- Required signature parameters: same as webhook

### Canonicalizer implementation details

The `Rfc9421Canonicalizer` follows RFC 9421 §2.5 exactly:

1. **Component resolution**: For each covered component name, resolve the component value from the request.
2. **Header normalization**: Lowercase field name, trim leading/trailing OWS, collapse inner OWS to single SP.
3. **`(request-target)`**: Constructed as `method SP request-target` (e.g., `POST /webhook`).
4. **`(authority)`**: Derived from the Host header, with default port stripping (443 for HTTPS, 80 for HTTP).
5. **`content-digest`**: Computed per RFC 9530 using SHA-256 (or SHA-512), encoded as `sha-256=:base64url-no-padding:`.
6. **Signature parameters line**: `@signature-params: (...)` with all parameters in required order.
7. **Base64url encoding**: No padding for `Signature` and `Content-Digest` values.

### URL canonicalization

The canonicalizer handles:
- IDN domains (punycode)
- IPv6 literals with brackets
- Default port stripping (443 for HTTPS, 80 for HTTP)
- Percent-encoding normalization (uppercase `%XX`)
- Path normalization (removing `.` and `..` segments)
- Query string preservation (byte-for-byte, no reordering)

### Test vector conformance

The implementation is tested against the AdCP compliance test vectors:
- `webhook-signing/positive/` (7 vectors)
- `webhook-signing/negative/` (21 vectors)
- `request-signing/canonicalization.json` (26 URL canonicalization cases)
- `request-signing/positive/` (multiple vectors)

Each vector's `expected_signature_base` is verified byte-for-byte against the canonicalizer output.

### Verification checklist implementation

The `Rfc9421Verifier` implements the full AdCP webhook verifier checklist (13 steps):

1. Parse `Signature-Input` and `Signature` headers (they MUST be a bound pair)
2. Validate required signature parameters (`created`, `expires`, `nonce`, `keyid`, `alg`, `tag`)
3. Validate `tag` matches expected profile (`adcp/webhook-signing/v1` or `adcp/request-signing/v1`)
4. Validate `alg` is allowed (`EdDSA`, `ES256`, `ES384`)
5. Validate timestamp window (`|now - created| <= 300s`, `expires > created`)
6. Validate required covered components are present (`@request-target`, `@authority`, `content-digest` for webhooks)
7. Look up key by `kid`
8. Validate `adcp_use` matches expected purpose
9. Validate key not revoked and key_ops includes `verify`
10. Verify signature bytes against canonical base
11. Verify `content-digest` matches body
12. Check nonce for replay (requires stateful runner)
13. Check rate limits (requires stateful runner)

Steps 12 and 13 require runner state and are tested separately in integration tests.

## What's deferred to v0.3

- `AccountStore` → `SigningContext` tenant resolution wiring
- `PrincipalRef` resolution from `adagents.json`
- JWKS fetching and caching (uses the SSRF-safe `AdcpHttpClient` from Track 3)
- Key rotation support in `VerificationKeyResolver`
- Legacy HMAC-SHA256 webhook signing (deprecated, removed in AdCP 4.0)

## References

- [RFC 9421](https://www.rfc-editor.org/rfc/rfc9421) — HTTP Message Signatures
- [RFC 9530](https://www.rfc-editor.org/rfc/rfc9530) — Digest Fields
- [AdCP Security](https://adcontextprotocol.org/docs/building/implementation/security) — AdCP security model
- [specs/signing-context.md](../specs/signing-context.md) — D22 SigningContext design