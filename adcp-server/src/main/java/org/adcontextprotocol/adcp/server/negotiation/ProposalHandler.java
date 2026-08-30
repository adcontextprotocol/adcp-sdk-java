package org.adcontextprotocol.adcp.server.negotiation;

import org.adcontextprotocol.adcp.negotiation.ProposalRefinement;
import org.adcontextprotocol.adcp.negotiation.RefinementCapability;
import org.adcontextprotocol.adcp.negotiation.RefinementResult;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsResponse;
import org.adcontextprotocol.adcp.server.AdcpContext;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * Server-side handler for proposal refinement operations.
 *
 * <p>Adopters implement this interface to handle incoming
 * {@code refine_proposals} requests. The framework performs
 * batch preflight validation (idempotency, cardinality, dimension
 * checks) before delegating to the handler. Commercial pricing
 * and optimization decisions are left to the application callback.
 *
 * <p>Example:
 * <pre>{@code
 * public class MyProposalHandler implements ProposalHandler {
 *     @Override
 *     public RefinementCapability capability() {
 *         return new RefinementCapability(
 *             Set.of("product_changes", "total_budget"), 10);
 *     }
 *
 *     @Override
 *     public List<RefinementResult> refine(
 *             List<ProposalRefinement> refinements,
 *             String idempotencyKey, AdcpContext ctx) {
 *         // commercial logic here
 *     }
 * }
 * }</pre>
 */
public interface ProposalHandler {

    /**
     * Declares this seller's refinement capabilities.
     *
     * <p>The returned capability is used for:
     * <ul>
     *   <li>Advertising supported dimensions to buyers</li>
     *   <li>Preflight validation of incoming requests</li>
     *   <li>Capability gating in the server builder</li>
     * </ul>
     */
    RefinementCapability capability();

    /**
     * Handles a batch of refinement operations.
     *
     * <p>The framework has already validated:
     * <ul>
     *   <li>Idempotency key format</li>
     *   <li>Batch size within the declared ceiling</li>
     *   <li>Finalize-only batch homogeneity</li>
     *   <li>Unique proposal IDs within the batch</li>
     * </ul>
     *
     * <p>The handler is responsible for:
     * <ul>
     *   <li>Loading and validating source proposals</li>
     *   <li>Creating immutable successor proposals</li>
     *   <li>Computing digest/lineage fields</li>
     *   <li>Atomic finalize transactions</li>
     *   <li>Idempotent replay detection</li>
     * </ul>
     *
     * @param refinements    validated refinement entries
     * @param idempotencyKey client-provided idempotency key
     * @param ctx            per-request context
     * @return results in request order, one per refinement entry
     */
    List<RefinementResult> refine(List<ProposalRefinement> refinements,
                                  String idempotencyKey, AdcpContext ctx);

    /**
     * Full-response hook for sellers that need to return canonical products,
     * asynchronous submission, context, or exact-replay metadata. The default
     * wraps {@link #refine} as a synchronous completed response.
     */
    default RefineProposalsResponse refineResponse(
            List<ProposalRefinement> refinements,
            String idempotencyKey, AdcpContext ctx) {
        return new RefineProposalsResponse(
                refine(refinements, idempotencyKey, ctx), List.of(), "completed",
                null, null, null, null, null, null, null);
    }

    /**
     * Applies a homogeneous finalize batch in one transaction.
     *
     * <p>The implementation must invoke {@code operation} inside its transaction
     * and commit only after it returns; the operation includes SDK response
     * validation. It must create every requested hold or create none and
     * implement exact idempotent replay for {@code idempotencyKey}.
     * The default fails closed so a seller cannot accidentally provide partial
     * finalization by routing finalize through {@link #refine}.
     */
    default RefineProposalsResponse finalizeAtomically(
            List<ProposalRefinement> refinements,
            String idempotencyKey, AdcpContext ctx,
            Supplier<RefineProposalsResponse> operation) {
        throw new UnsupportedOperationException(
                "finalizeAtomically must be implemented before advertising finalization");
    }

    /**
     * Optional hook called before the batch is dispatched to
     * {@link #refine}. Returns null to proceed, or an error
     * message to reject the batch.
     *
     * <p>Use this for cross-entry validation that the framework
     * cannot perform (e.g., checking that all source proposals
     * belong to the same context).
     */
    default @Nullable String preflight(List<ProposalRefinement> refinements,
                                       String idempotencyKey, AdcpContext ctx) {
        return null;
    }
}
