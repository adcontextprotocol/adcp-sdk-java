package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RefineProposalsRequestTest {

    private static String validKey() {
        return "idem-" + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void builder_creates_valid_single_revise_request() {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(validKey())
                .addRefinement(ProposalRefinement.revise("p-1", "lower CPM to $8"))
                .build();

        assertEquals(1, request.refinements().size());
        assertEquals("p-1", request.refinements().get(0).proposalId());
        assertEquals(RefinementAction.REVISE, request.refinements().get(0).action());
    }

    @Test
    void builder_creates_batch_finalize_request() {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(validKey())
                .addRefinement(ProposalRefinement.finalize("p-1"))
                .addRefinement(ProposalRefinement.finalize("p-2"))
                .addRefinement(ProposalRefinement.finalize("p-3"))
                .build();

        assertEquals(3, request.refinements().size());
        request.refinements().forEach(r ->
                assertEquals(RefinementAction.FINALIZE, r.action()));
    }

    @Test
    void rejects_null_idempotency_key() {
        var builder = RefineProposalsRequest.builder()
                .addRefinement(ProposalRefinement.revise("p-1", "test"));

        assertThrows(NullPointerException.class, builder::build);
    }

    @Test
    void rejects_short_idempotency_key() {
        assertThrows(IllegalArgumentException.class, () ->
                RefineProposalsRequest.builder()
                        .idempotencyKey("too-short")
                        .addRefinement(ProposalRefinement.revise("p-1", "test"))
                        .build());
    }

    @Test
    void rejects_empty_refinements() {
        assertThrows(IllegalArgumentException.class, () ->
                RefineProposalsRequest.builder()
                        .idempotencyKey(validKey())
                        .build());
    }

    @Test
    void rejects_mixed_finalize_and_revise_batch() {
        assertThrows(IllegalArgumentException.class, () ->
                RefineProposalsRequest.builder()
                        .idempotencyKey(validKey())
                        .addRefinement(ProposalRefinement.finalize("p-1"))
                        .addRefinement(ProposalRefinement.revise("p-2", "change CPM"))
                        .build());
    }

    @Test
    void rejects_duplicate_proposal_ids() {
        assertThrows(IllegalArgumentException.class, () ->
                RefineProposalsRequest.builder()
                        .idempotencyKey(validKey())
                        .addRefinement(ProposalRefinement.revise("p-1", "first"))
                        .addRefinement(ProposalRefinement.revise("p-1", "second"))
                        .build());
    }

    @Test
    void rejects_batch_exceeding_max_size() {
        var builder = RefineProposalsRequest.builder()
                .idempotencyKey(validKey())
                .maxBatchSize(2);

        builder.addRefinement(ProposalRefinement.revise("p-1", "a"));
        builder.addRefinement(ProposalRefinement.revise("p-2", "b"));
        builder.addRefinement(ProposalRefinement.revise("p-3", "c"));

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void refinements_list_is_immutable() {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(validKey())
                .addRefinement(ProposalRefinement.revise("p-1", "test"))
                .build();

        assertThrows(UnsupportedOperationException.class, () ->
                request.refinements().add(ProposalRefinement.revise("p-2", "x")));
    }

    @Test
    void cancellation_refinement() {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(validKey())
                .addRefinement(ProposalRefinement.cancel("p-1", "budget reallocated"))
                .build();

        var refinement = request.refinements().get(0);
        assertEquals(RefinementAction.REVISE, refinement.action());
        assertEquals(ChangeKind.CANCELLATION, refinement.changeKind());
    }

    @Test
    void rejects_alternatives_count_exceeding_protocol_max() {
        var mapper = new ObjectMapper();
        ObjectNode alt = mapper.createObjectNode().put("count", 11);

        assertThrows(IllegalArgumentException.class, () ->
                ProposalRefinement.builder("p-1")
                        .action(RefinementAction.REVISE)
                        .ask("give me options")
                        .alternatives(alt)
                        .build());
    }

    @Test
    void rejects_alternatives_count_below_minimum() {
        var mapper = new ObjectMapper();
        ObjectNode alt = mapper.createObjectNode().put("count", 1);

        assertThrows(IllegalArgumentException.class, () ->
                ProposalRefinement.builder("p-1")
                        .action(RefinementAction.REVISE)
                        .alternatives(alt)
                        .build());
    }

    @Test
    void accepts_valid_alternatives_count() {
        var mapper = new ObjectMapper();
        ObjectNode alt = mapper.createObjectNode().put("count", 5);

        var refinement = ProposalRefinement.builder("p-1")
                .action(RefinementAction.REVISE)
                .ask("five options")
                .alternatives(alt)
                .build();

        assertEquals(5, refinement.alternatives().get("count").asInt());
    }

    @Test
    void rejects_alternatives_exceeding_seller_ceiling() {
        var mapper = new ObjectMapper();
        ObjectNode alt = mapper.createObjectNode().put("count", 5);

        assertThrows(IllegalArgumentException.class, () ->
                RefineProposalsRequest.builder()
                        .idempotencyKey(validKey())
                        .maxAlternatives(3)
                        .addRefinement(ProposalRefinement.builder("p-1")
                                .action(RefinementAction.REVISE)
                                .ask("options")
                                .alternatives(alt)
                                .build())
                        .build());
    }

    @Test
    void revise_with_constraints() {
        var constraints = new RefinementConstraints(
                new TotalBudgetConstraint(new BigDecimal("5000"), new BigDecimal("10000"), "USD"),
                null, null, null);

        var refinement = ProposalRefinement.reviseWithConstraints("p-1", constraints);
        assertNotNull(refinement.constraints());
        assertEquals("USD", refinement.constraints().totalBudget().currency());
    }

    @Test
    void builder_creates_multi_field_refinement() {
        var mapper = new ObjectMapper();
        var constraints = new RefinementConstraints(
                new TotalBudgetConstraint(null, new BigDecimal("50000"), "EUR"),
                new CpmConstraint(new BigDecimal("12.50"), "EUR"),
                null, null);
        ObjectNode alt = mapper.createObjectNode().put("count", 3);

        var refinement = ProposalRefinement.builder("p-1")
                .action(RefinementAction.REVISE)
                .ask("lower CPM with alternatives")
                .constraints(constraints)
                .alternatives(alt)
                .build();

        assertEquals("p-1", refinement.proposalId());
        assertNotNull(refinement.constraints());
        assertNotNull(refinement.alternatives());
        assertEquals("lower CPM with alternatives", refinement.ask());
    }

    @Test
    void idempotency_replay_response() throws Exception {
        var mapper = new ObjectMapper();
        String json = """
                {
                    "status": "completed",
                    "replayed": true,
                    "results": [{
                        "source_proposal_id": "src-1",
                        "outcome": "revised",
                        "proposal": {"proposal_id": "p-new", "parent_proposal_id": "src-1", "proposal_status": "draft"}
                    }]
                }
                """;

        var response = mapper.readValue(json, RefineProposalsResponse.class);
        assertTrue(response.isCompleted());
        assertEquals(Boolean.TRUE, response.replayed());
    }
}
