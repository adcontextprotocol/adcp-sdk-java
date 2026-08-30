package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * Budget bounds constraint per {@code proposal-budget-constraint.json}.
 * Currency-aware inclusive bounds checked against {@code commercial_terms.total_budget}.
 *
 * @param min      minimum budget (inclusive), null if unconstrained below
 * @param max      maximum budget (inclusive), null if unconstrained above
 * @param currency ISO 4217 currency code
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TotalBudgetConstraint(
        @Nullable @JsonProperty("min") BigDecimal min,
        @Nullable @JsonProperty("max") BigDecimal max,
        @JsonProperty("currency") String currency) {

    public TotalBudgetConstraint {
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("budget currency must be a 3-letter ISO 4217 code");
        }
        if (min == null && max == null) {
            throw new IllegalArgumentException("budget must specify at least one bound");
        }
        if ((min != null && min.signum() < 0) || (max != null && max.signum() < 0)) {
            throw new IllegalArgumentException("budget bounds must be non-negative");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("budget min must not exceed max");
        }
    }
}
