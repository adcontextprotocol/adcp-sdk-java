package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request payload for the {@code refine_proposals} tool.
 *
 * <p>Builds a batch of refinement operations (revise or finalize) with
 * capability-aware validation: the builder enforces supported dimensions,
 * seller ceilings, and protocol cardinality before transport.
 *
 * @param idempotencyKey  client-generated key for retry safety (16-255 chars, alphanumeric + _.-)
 * @param refinements     ordered refinement operations, one per source proposal
 * @param contextId       optional context ID for the refinement session
 * @param context         optional context object
 * @param governanceContext optional governance/compliance context
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefineProposalsRequest(
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("refinements") List<ProposalRefinement> refinements,
        @Nullable @JsonProperty("context_id") String contextId,
        @Nullable @JsonProperty("context") JsonNode context,
        @Nullable @JsonProperty("governance_context") String governanceContext) {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9_.:-]{16,255}$");

    public RefineProposalsRequest {
        Objects.requireNonNull(idempotencyKey, "idempotency_key is required");
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "idempotency_key must match [A-Za-z0-9_.:-]{16,255}");
        }
        Objects.requireNonNull(refinements, "refinements is required");
        if (refinements.isEmpty()) {
            throw new IllegalArgumentException("refinements must not be empty");
        }
        refinements = List.copyOf(refinements);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private @Nullable String idempotencyKey;
        private final List<ProposalRefinement> refinements = new ArrayList<>();
        private @Nullable String contextId;
        private @Nullable JsonNode context;
        private @Nullable String governanceContext;
        private int maxBatchSize = 25;
        private int maxAlternatives = ProposalRefinement.MAX_ALTERNATIVES;

        private Builder() {}

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
            return this;
        }

        public Builder addRefinement(ProposalRefinement refinement) {
            this.refinements.add(Objects.requireNonNull(refinement));
            return this;
        }

        public Builder refinements(List<ProposalRefinement> refinements) {
            this.refinements.clear();
            this.refinements.addAll(refinements);
            return this;
        }

        public Builder contextId(String contextId) {
            this.contextId = contextId;
            return this;
        }

        public Builder context(JsonNode context) {
            this.context = context;
            return this;
        }

        public Builder governanceContext(String governanceContext) {
            this.governanceContext = governanceContext;
            return this;
        }

        /**
         * Sets the maximum batch size (default 25 per protocol spec).
         * The seller may advertise a lower ceiling.
         */
        public Builder maxBatchSize(int maxBatchSize) {
            if (maxBatchSize < 1) {
                throw new IllegalArgumentException("maxBatchSize must be positive");
            }
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        /**
         * Sets the seller's alternatives ceiling (default 10 per protocol spec).
         */
        public Builder maxAlternatives(int maxAlternatives) {
            if (maxAlternatives < 2) {
                throw new IllegalArgumentException("maxAlternatives must be >= 2");
            }
            this.maxAlternatives = maxAlternatives;
            return this;
        }

        public RefineProposalsRequest build() {
            validateBatch();
            return new RefineProposalsRequest(
                    idempotencyKey, refinements, contextId,
                    context, governanceContext);
        }

        private void validateBatch() {
            if (refinements.size() > maxBatchSize) {
                throw new IllegalArgumentException(
                        "batch size " + refinements.size()
                                + " exceeds maximum " + maxBatchSize);
            }

            // Enforce unique proposal IDs
            Set<String> ids = new HashSet<>();
            for (ProposalRefinement r : refinements) {
                if (!ids.add(r.proposalId())) {
                    throw new IllegalArgumentException(
                            "duplicate proposal_id in batch: " + r.proposalId());
                }
            }

            // Finalize batches must be homogeneous
            boolean hasFinalize = refinements.stream()
                    .anyMatch(r -> r.action() == RefinementAction.FINALIZE);
            boolean hasRevise = refinements.stream()
                    .anyMatch(r -> r.action() == RefinementAction.REVISE);
            if (hasFinalize && hasRevise) {
                throw new IllegalArgumentException(
                        "a batch containing finalize must contain only finalize entries");
            }

            // Validate alternatives count against seller ceiling
            for (ProposalRefinement r : refinements) {
                if (r.alternatives() != null) {
                    JsonNode count = r.alternatives().get("count");
                    if (count != null && count.asInt() > maxAlternatives) {
                        throw new IllegalArgumentException(
                                "alternatives.count " + count.asInt()
                                        + " exceeds seller maximum " + maxAlternatives);
                    }
                }
            }
        }
    }
}
