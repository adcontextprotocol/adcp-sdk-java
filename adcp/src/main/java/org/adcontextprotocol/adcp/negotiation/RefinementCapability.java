package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Declares a seller's refinement capabilities, advertised in the
 * agent's capability manifest.
 *
 * <p>The capability value dimension is {@code product_changes}
 * (renamed from draft-era {@code product_selection}).
 *
 * @param supportedDimensions the refinement dimensions this seller supports
 * @param maxBatchSize        maximum entries per refinement request (default: 25)
 * @param supportsFinalize    whether this seller supports the finalize action
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefinementCapability(
        @Nullable @JsonProperty("supported_dimensions") Set<String> supportedDimensions,
        @Nullable @JsonProperty("max_batch_size") Integer maxBatchSize,
        @Nullable @JsonProperty("supports_finalize") Boolean supportsFinalize) {

    /** Protocol default batch size when not declared by the seller. */
    public static final int DEFAULT_MAX_BATCH_SIZE = 25;

    /** The capability dimension key used in the agent manifest. */
    public static final String DIMENSION_KEY = "product_changes";

    public int effectiveMaxBatchSize() {
        return maxBatchSize != null ? maxBatchSize : DEFAULT_MAX_BATCH_SIZE;
    }
}
