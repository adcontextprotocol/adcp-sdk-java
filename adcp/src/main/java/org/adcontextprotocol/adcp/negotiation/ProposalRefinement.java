package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

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
        @Nullable @JsonProperty("product_changes") JsonNode productChanges,
        @Nullable @JsonProperty("alternatives") JsonNode alternatives) {

    /** Protocol maximum for alternatives.count. */
    public static final int MAX_ALTERNATIVES = 10;

    public ProposalRefinement {
        Objects.requireNonNull(proposalId, "proposal_id is required");
        if (proposalId.isBlank()) {
            throw new IllegalArgumentException("proposal_id must not be blank");
        }
        if (alternatives != null) {
            JsonNode count = alternatives.get("count");
            if (count == null || !count.isInt()) {
                throw new IllegalArgumentException("alternatives.count must be an integer");
            }
            int c = count.asInt();
            if (c < 2 || c > MAX_ALTERNATIVES) {
                throw new IllegalArgumentException(
                        "alternatives.count must be 2-" + MAX_ALTERNATIVES + ", got " + c);
            }
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
        private @Nullable JsonNode productChanges;
        private @Nullable JsonNode alternatives;

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

        public Builder productChanges(JsonNode productChanges) {
            this.productChanges = productChanges;
            return this;
        }

        public Builder alternatives(JsonNode alternatives) {
            this.alternatives = alternatives;
            return this;
        }

        public ProposalRefinement build() {
            return new ProposalRefinement(proposalId, action, changeKind,
                    ask, criteria, constraints, productChanges, alternatives);
        }
    }
}
