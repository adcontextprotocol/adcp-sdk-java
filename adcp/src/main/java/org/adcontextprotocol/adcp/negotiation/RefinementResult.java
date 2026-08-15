package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Sealed result for a single refinement entry in a response.
 *
 * <p>Discriminated on the {@code outcome} field. Pattern matching:
 * <pre>{@code
 * switch (result) {
 *     case RefinementResult.Revised r -> handleRevised(r);
 *     case RefinementResult.Partial p -> handlePartial(p);
 *     case RefinementResult.Finalized f -> handleFinalized(f);
 *     case RefinementResult.Unable u -> handleUnable(u);
 * }
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "outcome")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RefinementResult.Revised.class, name = "revised"),
        @JsonSubTypes.Type(value = RefinementResult.Partial.class, name = "partial"),
        @JsonSubTypes.Type(value = RefinementResult.Finalized.class, name = "finalized"),
        @JsonSubTypes.Type(value = RefinementResult.Unable.class, name = "unable")
})
public sealed interface RefinementResult {

    String sourceProposalId();

    RefinementOutcome outcome();

    /**
     * A successful full revision. The returned proposal is a draft with
     * all constraints satisfied.
     */
    record Revised(
            @JsonProperty("source_proposal_id") String sourceProposalId,
            @JsonProperty("proposal") JsonNode proposal,
            @Nullable @JsonProperty("targeting_resolution") JsonNode targetingResolution
    ) implements RefinementResult {
        @Override
        public RefinementOutcome outcome() {
            return RefinementOutcome.REVISED;
        }
    }

    /**
     * A partial revision. The proposal is a draft but some constraints
     * could not be fully satisfied. {@code unsatisfiedConstraints} names
     * which constraint keys from the request were not met.
     *
     * <p>Invariant: every constraint <em>not</em> listed in
     * {@code unsatisfiedConstraints} is fully satisfied by this draft.
     */
    record Partial(
            @JsonProperty("source_proposal_id") String sourceProposalId,
            @JsonProperty("proposal") JsonNode proposal,
            @JsonProperty("notes") String notes,
            @Nullable @JsonProperty("unsatisfied_constraints") List<String> unsatisfiedConstraints,
            @Nullable @JsonProperty("suggestions") List<String> suggestions,
            @Nullable @JsonProperty("targeting_resolution") JsonNode targetingResolution
    ) implements RefinementResult {
        @Override
        public RefinementOutcome outcome() {
            return RefinementOutcome.PARTIAL;
        }
    }

    /**
     * Successful finalization: inventory is reserved, the proposal is
     * now committed with a firm {@code expires_at}.
     */
    record Finalized(
            @JsonProperty("source_proposal_id") String sourceProposalId,
            @JsonProperty("proposal") JsonNode proposal
    ) implements RefinementResult {
        @Override
        public RefinementOutcome outcome() {
            return RefinementOutcome.FINALIZED;
        }
    }

    /**
     * The refinement could not be performed. The reason indicates why.
     *
     * <p>Reason code precedence: {@code constraint_unsatisfiable} wins
     * whenever a typed constraint failed; typed failures never surface
     * as {@code commercially_declined}.
     */
    record Unable(
            @JsonProperty("source_proposal_id") String sourceProposalId,
            @JsonProperty("reason") String reason,
            @Nullable @JsonProperty("notes") String notes,
            @Nullable @JsonProperty("suggestions") List<String> suggestions
    ) implements RefinementResult {
        @Override
        public RefinementOutcome outcome() {
            return RefinementOutcome.UNABLE;
        }
    }
}
