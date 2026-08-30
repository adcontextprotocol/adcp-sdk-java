# Proposal negotiation

The SDK models `refine_proposals` as task-level errors plus sealed per-proposal
outcomes. Always discover `proposal_refinement.supported_dimensions` first, pass
the resulting `RefinementCapability` to `RefineProposalsRequest.Builder`, and run
`ResponseVerifier.verify` before selecting or finalizing a returned draft.

Run the public constrained-seller scenario with:

```shell
export ADCP_AUTH_TOKEN="..."
./gradlew :adcp-cli:run --args='proposal-negotiation'
```

The example requests three alternatives, verifies the deterministic two-draft
`partial` counteroffer, then changes the count to two with a new idempotency key.
Exact transport retries must reuse the original key and byte-equivalent request.

## Seller registration and atomic finalize

Register a `ProposalHandler` with `AdcpServerBuilder.proposalHandler`. The server
validates cardinality, batch shape, typed dimensions, application preflight, and
the returned lineage/digests before responding. Revise calls go to `refine`.
Homogeneous finalize batches go only to `finalizeAtomically`; its default fails
closed. Invoke the supplied operation inside the transaction and commit only
after it returns, so SDK response validation happens before persistence.

Use `ProposalSuccessor.stamp` for draft successors and
`ProposalSuccessor.stampFinalized` for committed holds. Both assign a fresh ID,
preserve parent lineage, require commercial terms, and recompute the RFC 8785
digest.

## Legacy compatibility limits

Legacy `get_products` refinement and compact `refine_proposals` are not generally
interchangeable. A discovery `budget_range` filters candidate products, while
`constraints.total_budget` is a hard post-generation assertion over complete
commercial terms. Never map one to the other, move a typed constraint into
`ask`, weaken inclusive bounds, or convert currency.

Likewise, compact finalize may be adapted only when the legacy operation can
guarantee byte-equivalent complete commercial terms and atomic holds. If any
requested field has no lossless mapping, reject the entire operation before
dispatch; do not silently drop it or repair terms after mutation.
