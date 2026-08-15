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
        if (max == null) throw new IllegalArgumentException("cpm max is required");
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("cpm currency is required");
        }
    }
}
