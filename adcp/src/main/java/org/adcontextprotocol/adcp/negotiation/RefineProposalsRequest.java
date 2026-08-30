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
        @Nullable @JsonProperty("adcp_version") String adcpVersion,
        @Nullable @JsonProperty("adcp_major_version") Integer adcpMajorVersion,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("refinements") List<ProposalRefinement> refinements,
        @Nullable @JsonProperty("context_id") String contextId,
        @Nullable @JsonProperty("context") JsonNode context,
        @Nullable @JsonProperty("governance_context") String governanceContext,
        @Nullable @JsonProperty("push_notification_config") JsonNode pushNotificationConfig) {

    /** Protocol maximum number of refinement entries in one request. */
    public static final int PROTOCOL_MAX_REFINEMENTS = 25;

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
        if (refinements.size() > PROTOCOL_MAX_REFINEMENTS) {
            throw new IllegalArgumentException(
                    "refinements must contain at most " + PROTOCOL_MAX_REFINEMENTS + " entries");
        }
        refinements = List.copyOf(refinements);
        validateBatchShape(refinements, PROTOCOL_MAX_REFINEMENTS);
    }

    public RefineProposalsRequest(String idempotencyKey,
                                  List<ProposalRefinement> refinements,
                                  @Nullable String contextId,
                                  @Nullable JsonNode context,
                                  @Nullable String governanceContext) {
        this(null, null, idempotencyKey, refinements, contextId, context,
                governanceContext, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Validates typed dimensions and alternatives against seller capabilities. */
    public void validateAgainst(RefinementCapability capability) {
        Objects.requireNonNull(capability, "capability");
        validateCapabilities(refinements, capability.supportedDimensions(),
                capability.effectiveMaxAlternatives());
    }

    public static final class Builder {
        private @Nullable String idempotencyKey;
        private final List<ProposalRefinement> refinements = new ArrayList<>();
        private @Nullable String contextId;
        private @Nullable JsonNode context;
        private @Nullable String governanceContext;
        private @Nullable JsonNode pushNotificationConfig;
        private @Nullable String adcpVersion;
        private @Nullable Integer adcpMajorVersion;
        private int maxBatchSize = PROTOCOL_MAX_REFINEMENTS;
        private int maxAlternatives = ProposalRefinement.MAX_ALTERNATIVES;
        private @Nullable Set<String> supportedDimensions;

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

        public Builder pushNotificationConfig(JsonNode pushNotificationConfig) {
            this.pushNotificationConfig = pushNotificationConfig;
            return this;
        }

        public Builder adcpVersion(String adcpVersion) {
            this.adcpVersion = adcpVersion;
            return this;
        }

        public Builder adcpMajorVersion(int adcpMajorVersion) {
            this.adcpMajorVersion = adcpMajorVersion;
            return this;
        }

        /** Applies the seller-advertised typed-dimension and alternatives limits. */
        public Builder capability(RefinementCapability capability) {
            Objects.requireNonNull(capability, "capability");
            this.supportedDimensions = capability.supportedDimensions();
            this.maxAlternatives = capability.effectiveMaxAlternatives();
            return this;
        }

        /**
         * Sets the maximum batch size (default 25 per protocol spec).
         * The seller may advertise a lower ceiling.
         */
        public Builder maxBatchSize(int maxBatchSize) {
            if (maxBatchSize < 1 || maxBatchSize > PROTOCOL_MAX_REFINEMENTS) {
                throw new IllegalArgumentException(
                        "maxBatchSize must be 1-" + PROTOCOL_MAX_REFINEMENTS);
            }
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        /**
         * Sets the seller's alternatives ceiling (default 10 per protocol spec).
         */
        public Builder maxAlternatives(int maxAlternatives) {
            if (maxAlternatives < 2 || maxAlternatives > ProposalRefinement.MAX_ALTERNATIVES) {
                throw new IllegalArgumentException("maxAlternatives must be 2-10");
            }
            this.maxAlternatives = maxAlternatives;
            return this;
        }

        public RefineProposalsRequest build() {
            validateBatch();
            return new RefineProposalsRequest(
                    adcpVersion, adcpMajorVersion, idempotencyKey, refinements, contextId,
                    context, governanceContext, pushNotificationConfig);
        }

        private void validateBatch() {
            validateBatchShape(refinements, maxBatchSize);
            if (supportedDimensions != null) {
                validateCapabilities(refinements, supportedDimensions, maxAlternatives);
            } else {
                validateAlternatives(refinements, maxAlternatives);
            }
        }

        private static Set<String> requestedDimensions(ProposalRefinement refinement) {
            Set<String> dimensions = new HashSet<>();
            if (refinement.constraints() != null) {
                if (refinement.constraints().totalBudget() != null) dimensions.add("total_budget");
                if (refinement.constraints().cpm() != null) dimensions.add("cpm");
                if (refinement.constraints().impressions() != null) dimensions.add("impressions");
                if (refinement.constraints().flight() != null) dimensions.add("flight");
            }
            if (refinement.productChanges() != null && !refinement.productChanges().isEmpty()) {
                dimensions.add("product_changes");
            }
            if (refinement.alternatives() != null) dimensions.add("alternatives");
            if (refinement.criteria() != null) dimensions.add("criteria");
            return dimensions;
        }
    }

    private static void validateBatchShape(List<ProposalRefinement> refinements, int maximum) {
        if (refinements.size() > maximum) {
            throw new IllegalArgumentException(
                    "batch size " + refinements.size() + " exceeds maximum " + maximum);
        }
        Set<String> ids = new HashSet<>();
        boolean hasFinalize = false;
        boolean hasRevise = false;
        for (ProposalRefinement refinement : refinements) {
            if (!ids.add(refinement.proposalId())) {
                throw new IllegalArgumentException(
                        "duplicate proposal_id in batch: " + refinement.proposalId());
            }
            if (refinement.action() == RefinementAction.FINALIZE) {
                hasFinalize = true;
            } else {
                hasRevise = true;
            }
        }
        if (hasFinalize && hasRevise) {
            throw new IllegalArgumentException(
                    "a batch containing finalize must contain only finalize entries");
        }
    }

    private static void validateCapabilities(List<ProposalRefinement> refinements,
                                             Set<String> supportedDimensions,
                                             int maxAlternatives) {
        validateAlternatives(refinements, maxAlternatives);
        for (ProposalRefinement refinement : refinements) {
            for (String dimension : Builder.requestedDimensions(refinement)) {
                if (!supportedDimensions.contains(dimension)) {
                    throw new UnsupportedRefinementException(
                            new UnsupportedRefinementDetails(
                                    dimension, List.copyOf(supportedDimensions)));
                }
            }
        }
    }

    private static void validateAlternatives(List<ProposalRefinement> refinements,
                                             int maxAlternatives) {
        for (ProposalRefinement refinement : refinements) {
            if (refinement.alternatives() != null
                    && refinement.alternatives().count() > maxAlternatives) {
                throw new IllegalArgumentException(
                        "alternatives.count " + refinement.alternatives().count()
                                + " exceeds seller maximum " + maxAlternatives);
            }
        }
    }
}
