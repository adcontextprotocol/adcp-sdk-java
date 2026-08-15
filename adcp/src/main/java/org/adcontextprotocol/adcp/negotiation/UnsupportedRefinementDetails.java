package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Typed error details for {@code UNSUPPORTED_FEATURE} when a refinement
 * dimension is not supported by the seller.
 *
 * <p>Follows the {@code error-details/unsupported-refinement-dimension.json}
 * schema: carries the unsupported dimension and echoes back the seller's
 * supported dimensions for typed error recovery.
 *
 * @param unsupportedDimension  the dimension the buyer requested
 * @param supportedDimensions   the dimensions the seller actually supports
 */
public record UnsupportedRefinementDetails(
        @JsonProperty("unsupported_dimension") String unsupportedDimension,
        @JsonProperty("supported_dimensions") List<String> supportedDimensions) {
}
