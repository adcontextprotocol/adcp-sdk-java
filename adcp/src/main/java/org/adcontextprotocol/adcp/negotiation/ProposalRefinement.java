package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A single refinement operation within a {@link RefineProposalsRequest}.
 *
 * <p>Revising with structured criteria and/or semantic instructions creates
 * a new draft; finalizing changes no terms. Refining an accepted proposal
 * creates a draft amendment or cancellation proposal.
 *
 * @param proposalId the source proposal to refine
 * @param action     revise or finalize
 * @param changeKind amendment (default) or cancellation; only valid for accepted sources
 * @param instructions semantic commercial changes or cancellation reason
 * @param criteria   structured changes; each present field replaces that criterion
 */
public record ProposalRefinement(
        @JsonProperty("proposal_id") String proposalId,
        @Nullable @JsonProperty("action") RefinementAction action,
        @Nullable @JsonProperty("change_kind") ChangeKind changeKind,
        @Nullable @JsonProperty("instructions") String instructions,
        @Nullable @JsonProperty("criteria") JsonNode criteria) {

    public ProposalRefinement {
        Objects.requireNonNull(proposalId, "proposal_id is required");
        if (proposalId.isBlank()) {
            throw new IllegalArgumentException("proposal_id must not be blank");
        }
    }

    /**
     * Creates a revise refinement with instructions.
     */
    public static ProposalRefinement revise(String proposalId, String instructions) {
        return new ProposalRefinement(proposalId, RefinementAction.REVISE,
                null, instructions, null);
    }

    /**
     * Creates a revise refinement with structured criteria.
     */
    public static ProposalRefinement reviseWithCriteria(String proposalId, JsonNode criteria) {
        return new ProposalRefinement(proposalId, RefinementAction.REVISE,
                null, null, criteria);
    }

    /**
     * Creates a finalize refinement (no term changes, reserves inventory).
     */
    public static ProposalRefinement finalize(String proposalId) {
        return new ProposalRefinement(proposalId, RefinementAction.FINALIZE,
                null, null, null);
    }

    /**
     * Creates a cancellation refinement against an accepted proposal.
     */
    public static ProposalRefinement cancel(String proposalId, String reason) {
        return new ProposalRefinement(proposalId, RefinementAction.REVISE,
                ChangeKind.CANCELLATION, reason, null);
    }
}
