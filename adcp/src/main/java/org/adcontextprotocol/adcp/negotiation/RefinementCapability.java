package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Set;

/**
 * Declares a seller's refinement capabilities, advertised in the
 * agent's capability manifest.
 *
 * <p>The capability value dimension is {@code product_changes}
 * (renamed from draft-era {@code product_selection}).
 *
 * @param supportedDimensions the refinement dimensions this seller supports
 * @param maxAlternatives     maximum alternatives.count the seller accepts (default: 10)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefinementCapability(
        @JsonProperty("supported_dimensions") Set<String> supportedDimensions,
        @Nullable @JsonProperty("max_alternatives") Integer maxAlternatives) {

    /** The capability dimension key used in the agent manifest. */
    public static final String DIMENSION_KEY = "product_changes";

    public RefinementCapability {
        supportedDimensions = Set.copyOf(Objects.requireNonNull(
                supportedDimensions, "supported_dimensions is required"));
        if (maxAlternatives != null
                && (maxAlternatives < 2 || maxAlternatives > AlternativesRequest.PROTOCOL_MAX)) {
            throw new IllegalArgumentException("max_alternatives must be 2-10");
        }
        if (maxAlternatives != null && !supportedDimensions.contains("alternatives")) {
            throw new IllegalArgumentException(
                    "max_alternatives requires alternatives in supported_dimensions");
        }
    }

    public int effectiveMaxAlternatives() {
        return maxAlternatives != null ? maxAlternatives : ProposalRefinement.MAX_ALTERNATIVES;
    }
}
