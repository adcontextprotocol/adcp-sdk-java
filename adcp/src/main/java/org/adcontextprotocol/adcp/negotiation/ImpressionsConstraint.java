package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Minimum summed impressions constraint across all purchases.
 *
 * @param min minimum total impressions required
 */
public record ImpressionsConstraint(
        @JsonProperty("min") long min) {

    public ImpressionsConstraint {
        if (min < 0) throw new IllegalArgumentException("impressions min must be non-negative");
    }
}
