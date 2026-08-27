package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Typed constraint envelope for a refinement entry. At least one
 * constraint must be present per the schema's {@code minProperties: 1}.
 *
 * @param totalBudget inclusive budget bounds
 * @param cpm         CPM ceiling across all purchases
 * @param impressions minimum summed impressions
 * @param flight      flight-window timing bounds
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefinementConstraints(
        @Nullable @JsonProperty("total_budget") TotalBudgetConstraint totalBudget,
        @Nullable @JsonProperty("cpm") CpmConstraint cpm,
        @Nullable @JsonProperty("impressions") ImpressionsConstraint impressions,
        @Nullable @JsonProperty("flight") FlightConstraint flight) {

    public RefinementConstraints {
        if (totalBudget == null && cpm == null && impressions == null && flight == null) {
            throw new IllegalArgumentException("constraints must specify at least one dimension");
        }
    }
}
