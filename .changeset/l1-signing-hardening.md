---
"adcp-server": minor
"adcp-signing-aws-kms": patch
"adcp-signing-gcp-kms": patch
"adcp": patch
---

L1 signing: RFC 9421 verification hardening, JWK alg/kty/crv validation, KMS provider fixes.

- Reject duplicate Signature-Input labels and unquoted string params (parser differential fix)
- Validate JWK alg/kty/crv consistency per RFC 8037 before key-material parsing
- Use request_signature_* error taxonomy for request-signing verification failures
- Allow ecdsa-p384-sha384 in the AdCP signature profile algorithm allowlist
- AWS/GCP KMS: emit content-digest for empty webhook bodies
- GCP KMS: branch by algorithm (setData for Ed25519, setDigest for ECDSA)
- AdcpUse.fromWireName accepts long-form wire names from conformance vectors
- Verifier validates content-digest against body for empty-body webhooks