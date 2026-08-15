package org.adcontextprotocol.adcp.negotiation;

import org.junit.jupiter.api.Test;

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
}
