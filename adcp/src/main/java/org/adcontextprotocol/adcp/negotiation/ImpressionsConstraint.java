package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Minimum summed impressions constraint across all purchases.
 *
 * @param min minimum total impressions required
 */
public record ImpressionsConstraint(
        @JsonProperty("min") BigDecimal min) {

    public ImpressionsConstraint {
        if (min == null || min.signum() <= 0) {
            throw new IllegalArgumentException("impressions min must be positive");
        }
    }

    public ImpressionsConstraint(long min) {
        this(BigDecimal.valueOf(min));
    }
}
