package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * CPM ceiling constraint: every purchase must be priced at fixed CPM/vCPM
 * in the given currency at or under max.
 *
 * @param max     maximum CPM value
 * @param currency ISO 4217 currency code
 */
public record CpmConstraint(
        @JsonProperty("max") BigDecimal max,
        @JsonProperty("currency") String currency) {

    public CpmConstraint {
        if (max == null || max.signum() <= 0) {
            throw new IllegalArgumentException("cpm max must be positive");
        }
        if (currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("cpm currency must be a 3-letter ISO 4217 code");
        }
    }
}
