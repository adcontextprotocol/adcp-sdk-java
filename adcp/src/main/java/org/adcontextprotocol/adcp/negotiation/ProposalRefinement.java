package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * A single refinement operation within a {@link RefineProposalsRequest}.
 *
 * <p>Maps to {@code proposal-refinement.json}. Use {@link #builder(String)}
 * for multi-field requests; factory methods cover common single-concern cases.
 *
 * @param proposalId     the source proposal to refine
 * @param action         revise or finalize
 * @param changeKind     amendment (default) or cancellation; only valid for accepted sources
 * @param ask            semantic commercial changes or cancellation reason
 * @param criteria       structured discovery changes; each present field replaces that criterion
 * @param constraints    typed hard requirements (budget, CPM, impressions, flight)
 * @param productChanges product IDs mapped to include/omit actions
 * @param alternatives   alternatives request ({@code {"count": 2..10}})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProposalRefinement(
        @JsonProperty("proposal_id") String proposalId,
        @Nullable @JsonProperty("action") RefinementAction action,
        @Nullable @JsonProperty("change_kind") ChangeKind changeKind,
        @Nullable @JsonProperty("ask") String ask,
        @Nullable @JsonProperty("criteria") JsonNode criteria,
        @Nullable @JsonProperty("constraints") RefinementConstraints constraints,
        @Nullable @JsonProperty("product_changes") Map<String, String> productChanges,
        @Nullable @JsonProperty("alternatives") AlternativesRequest alternatives) {

    /** Protocol maximum for alternatives.count. */
    public static final int MAX_ALTERNATIVES = AlternativesRequest.PROTOCOL_MAX;

    public ProposalRefinement {
        Objects.requireNonNull(proposalId, "proposal_id is required");
        if (proposalId.isBlank()) {
            throw new IllegalArgumentException("proposal_id must not be blank");
        }
        if (productChanges != null) {
            productChanges = Map.copyOf(productChanges);
            for (var entry : productChanges.entrySet()) {
                if (entry.getKey().isBlank()
                        || !("include".equals(entry.getValue()) || "omit".equals(entry.getValue()))) {
                    throw new IllegalArgumentException(
                            "product_changes must map non-blank product IDs to include or omit");
                }
            }
        }
        RefinementAction effectiveAction = action != null ? action : RefinementAction.REVISE;
        if (effectiveAction == RefinementAction.FINALIZE) {
            if (changeKind != null || ask != null || criteria != null || constraints != null
                    || (productChanges != null && !productChanges.isEmpty()) || alternatives != null) {
                throw new IllegalArgumentException("finalize cannot change proposal terms");
            }
        } else if (changeKind != ChangeKind.CANCELLATION
                && (ask == null || ask.isBlank()) && criteria == null && constraints == null
                && (productChanges == null || productChanges.isEmpty()) && alternatives == null) {
            throw new IllegalArgumentException("revise requires at least one requested change");
        }
    }

    /**
     * Creates a revise refinement with a semantic ask.
     */
    public static ProposalRefinement revise(String proposalId, String ask) {
        return new ProposalRefinement(proposalId, RefinementAction.REVISE,
                null, ask, null, null, null, null);
    }

    /**
     * Creates a revise refinement with structured criteria.
     */
    public static ProposalRefinement reviseWithCriteria(String proposalId, JsonNode criteria) {
        return new ProposalRefinement(proposalId, RefinementAction.REVISE,
                null, null, criteria, null, null, null);
    }

    /**
     * Creates a revise refinement with typed constraints.
     */
    public static ProposalRefinement reviseWithConstraints(
            String proposalId, RefinementConstraints constraints) {
        return new ProposalRefinement(proposalId, RefinementAction.REVISE,
                null, null, null, constraints, null, null);
    }

    /**
     * Creates a finalize refinement (no term changes, reserves inventory).
     */
    public static ProposalRefinement finalize(String proposalId) {
        return new ProposalRefinement(proposalId, RefinementAction.FINALIZE,
                null, null, null, null, null, null);
    }

    /**
     * Creates a cancellation refinement against an accepted proposal.
     */
    public static ProposalRefinement cancel(String proposalId, String reason) {
        return new ProposalRefinement(proposalId, RefinementAction.REVISE,
                ChangeKind.CANCELLATION, reason, null, null, null, null);
    }

    public static Builder builder(String proposalId) {
        return new Builder(proposalId);
    }

    public static final class Builder {
        private final String proposalId;
        private @Nullable RefinementAction action;
        private @Nullable ChangeKind changeKind;
        private @Nullable String ask;
        private @Nullable JsonNode criteria;
        private @Nullable RefinementConstraints constraints;
        private @Nullable Map<String, String> productChanges;
        private @Nullable AlternativesRequest alternatives;

        private Builder(String proposalId) {
            this.proposalId = Objects.requireNonNull(proposalId);
        }

        public Builder action(RefinementAction action) {
            this.action = action;
            return this;
        }

        public Builder changeKind(ChangeKind changeKind) {
            this.changeKind = changeKind;
            return this;
        }

        public Builder ask(String ask) {
            this.ask = ask;
            return this;
        }

        public Builder criteria(JsonNode criteria) {
            this.criteria = criteria;
            return this;
        }

        public Builder constraints(RefinementConstraints constraints) {
            this.constraints = constraints;
            return this;
        }

        public Builder productChanges(Map<String, String> productChanges) {
            this.productChanges = productChanges;
            return this;
        }

        public Builder alternatives(AlternativesRequest alternatives) {
            this.alternatives = alternatives;
            return this;
        }

        public Builder alternatives(int count) {
            return alternatives(new AlternativesRequest(count));
        }

        public ProposalRefinement build() {
            return new ProposalRefinement(proposalId, action, changeKind,
                    ask, criteria, constraints, productChanges, alternatives);
        }
    }
}
