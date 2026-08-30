package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResponseVerifierTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void valid_revised_response_passes() {
        var request = request(ProposalRefinement.revise("src-1", "lower price"));
        var response = response(new RefinementResult.Revised(
                "src-1", List.of(proposal("new-1", "src-1", 10)), null));
        assertEquals(List.of(), ResponseVerifier.verify(request, response));
    }

    @Test
    void verifies_order_count_fresh_identity_lineage_and_digest() {
        var request = RefineProposalsRequest.builder().idempotencyKey(key())
                .addRefinement(ProposalRefinement.revise("src-1", "a"))
                .addRefinement(ProposalRefinement.revise("src-2", "b"))
                .build();
        ObjectNode bad = proposal("src-2", "wrong-parent", 10);
        bad.put("terms_digest", "sha256:bad");
        var response = response(new RefinementResult.Revised(
                "src-2", List.of(bad), null));
        List<String> violations = ResponseVerifier.verify(request, response);
        assertContains(violations, "result count");
        assertContains(violations, "request order");
        assertContains(violations, "fresh proposal_id");
        assertContains(violations, "parent_proposal_id");
        assertContains(violations, "terms_digest");
    }

    @Test
    void alternatives_require_exact_count_and_distinct_terms() {
        var request = request(ProposalRefinement.builder("src-1").alternatives(2).build());
        var response = response(new RefinementResult.Revised("src-1", List.of(
                proposal("new-1", "src-1", 10),
                proposal("new-2", "src-1", 10)), null));
        assertContains(ResponseVerifier.verify(request, response), "duplicates commercial_terms");

        var shortResponse = response(new RefinementResult.Revised(
                "src-1", List.of(proposal("new-3", "src-1", 11)), null));
        assertContains(ResponseVerifier.verify(request, shortResponse), "expected 2");
    }

    @Test
    void typed_constraints_are_fail_closed_for_every_purchase() {
        var constraints = new RefinementConstraints(
                new TotalBudgetConstraint(new BigDecimal("5000"), new BigDecimal("10000"), "USD"),
                new CpmConstraint(new BigDecimal("12"), "USD"),
                new ImpressionsConstraint(new BigDecimal("100000")), null);
        var request = request(ProposalRefinement.reviseWithConstraints("src-1", constraints));
        ObjectNode p = proposal("new-1", "src-1", 10);
        ObjectNode terms = (ObjectNode) p.get("commercial_terms");
        terms.set("total_budget", mapper.createObjectNode()
                .put("amount", 7500).put("currency", "USD"));
        // Missing impressions and pricing must fail closed, not be skipped.
        terms.putArray("purchases").addObject().put("product_id", "prod-1");
        p.put("terms_digest", TermsDigest.compute(terms));
        List<String> violations = ResponseVerifier.verify(request,
                response(new RefinementResult.Revised("src-1", List.of(p), null)));
        assertContains(violations, "cpm constraint");
        assertContains(violations, "impressions constraint cannot be verified");
    }

    @Test
    void partial_enforces_failure_subsets_precedence_and_unlisted_constraints() {
        var constraints = new RefinementConstraints(
                new TotalBudgetConstraint(null, new BigDecimal("100"), "USD"), null, null, null);
        var refinement = ProposalRefinement.builder("src-1")
                .constraints(constraints)
                .productChanges(Map.of("prod-1", "include"))
                .build();
        var request = request(refinement);
        var partial = new RefinementResult.Partial(
                "src-1", List.of(proposal("new-1", "src-1", 10)),
                ProposalRefinementReason.COMMERCIALLY_DECLINED, "no",
                List.of("made_up"), Map.of("prod-2", "omit"), null, null);
        List<String> violations = ResponseVerifier.verify(request, response(partial));
        assertContains(violations, "unrequested constraint");
        assertContains(violations, "unrequested product change");
        assertContains(violations, "constraint_unsatisfiable precedence");
        assertContains(violations, "total_budget constraint cannot be verified");
        assertContains(violations, "product changes cannot be verified");
    }

    @Test
    void finalize_is_atomic_committed_and_expiry_aware() {
        var request = RefineProposalsRequest.builder().idempotencyKey(key())
                .addRefinement(ProposalRefinement.finalize("src-1"))
                .addRefinement(ProposalRefinement.finalize("src-2"))
                .build();
        ObjectNode committed = proposal("new-1", "src-1", 10);
        committed.put("proposal_status", "committed");
        committed.put("expires_at", "2026-01-01T00:00:00Z");
        var response = response(
                new RefinementResult.Finalized("src-1", committed),
                new RefinementResult.Unable("src-2",
                        ProposalRefinementReason.HOLD_UNAVAILABLE, "sold", null, null, null));
        List<String> violations = ResponseVerifier.verify(
                request, response, Instant.parse("2026-08-30T00:00:00Z"));
        assertContains(violations, "hold is expired");
        assertContains(violations, "holds are not atomic");
    }

    @Test
    void fully_rolled_back_finalize_failure_is_valid() {
        var request = RefineProposalsRequest.builder().idempotencyKey(key())
                .addRefinement(ProposalRefinement.finalize("src-1"))
                .addRefinement(ProposalRefinement.finalize("src-2"))
                .build();
        var response = response(
                new RefinementResult.Unable("src-1",
                        ProposalRefinementReason.HOLD_UNAVAILABLE, "sold", null, null, null),
                new RefinementResult.Unable("src-2",
                        ProposalRefinementReason.BATCH_ABORTED, "sibling failed", null, null, null));
        assertEquals(List.of(), ResponseVerifier.verify(request, response));
    }

    @Test
    void submitted_response_has_no_results() {
        var request = request(ProposalRefinement.revise("src-1", "test"));
        var submitted = new RefineProposalsResponse(null, null, "submitted",
                "task-1", null, null, null, null, null, null);
        assertEquals(List.of(), ResponseVerifier.verify(request, submitted));
    }

    private RefineProposalsRequest request(ProposalRefinement refinement) {
        return RefineProposalsRequest.builder().idempotencyKey(key())
                .addRefinement(refinement).build();
    }

    private RefineProposalsResponse response(RefinementResult... results) {
        return new RefineProposalsResponse(List.of(results), List.of(), "completed",
                null, null, null, null, null, null, null);
    }

    private ObjectNode proposal(String id, String parent, int price) {
        ObjectNode terms = mapper.createObjectNode().put("price", price);
        ObjectNode proposal = mapper.createObjectNode()
                .put("proposal_id", id)
                .put("parent_proposal_id", parent)
                .put("proposal_status", "draft");
        proposal.set("commercial_terms", terms);
        proposal.put("terms_digest", TermsDigest.compute(terms));
        return proposal;
    }

    private static String key() {
        return "idem-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void assertContains(List<String> violations, String expected) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(expected)),
                () -> "Expected '" + expected + "' in " + violations);
    }
}
