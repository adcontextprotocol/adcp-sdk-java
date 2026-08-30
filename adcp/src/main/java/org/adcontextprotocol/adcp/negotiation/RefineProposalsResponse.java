package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Response from the {@code refine_proposals} tool.
 *
 * <p>Synchronous completions carry {@code results} and {@code products}.
 * Asynchronous responses carry a {@code taskId} for polling.
 *
 * @param results  ordered results, one per requested refinement
 * @param products compact canonical products referenced by the results
 * @param status   "completed" or "submitted" (for async)
 * @param taskId   non-null when status is "submitted"
 * @param message  optional human-readable status message
 * @param errors   optional error array from the response
 * @param adcpVersion release-precision wire version
 * @param context opaque correlation context echoed by the seller
 * @param ext registered extension data
 * @param replayed true when this is a replayed idempotent response
 */
public record RefineProposalsResponse(
        @Nullable @JsonProperty("results") List<RefinementResult> results,
        @Nullable @JsonProperty("products") List<JsonNode> products,
        @Nullable @JsonProperty("status") String status,
        @Nullable @JsonProperty("task_id") String taskId,
        @Nullable @JsonProperty("message") String message,
        @Nullable @JsonProperty("errors") List<JsonNode> errors,
        @Nullable @JsonProperty("adcp_version") String adcpVersion,
        @Nullable @JsonProperty("context") JsonNode context,
        @Nullable @JsonProperty("ext") JsonNode ext,
        @Nullable @JsonProperty("replayed") Boolean replayed) {

    public RefineProposalsResponse {
        results = results == null ? null : List.copyOf(results);
        products = products == null ? null : List.copyOf(products);
        errors = errors == null ? null : List.copyOf(errors);
    }

    /**
     * Whether this is a synchronous completed response.
     */
    public boolean isCompleted() {
        return "completed".equals(status) || (results != null && taskId == null);
    }

    /**
     * Whether this response was deferred for async processing.
     */
    public boolean isAsync() {
        return "submitted".equals(status);
    }
}
