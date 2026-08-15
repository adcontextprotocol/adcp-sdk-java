package org.adcontextprotocol.adcp.testing.negotiation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.adcontextprotocol.adcp.negotiation.CpmConstraint;
import org.adcontextprotocol.adcp.negotiation.FlightConstraint;
import org.adcontextprotocol.adcp.negotiation.ImpressionsConstraint;
import org.adcontextprotocol.adcp.negotiation.ProposalRefinement;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsRequest;
import org.adcontextprotocol.adcp.negotiation.TermsDigest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Shared test fixtures for proposal negotiation tests.
 *
 * <p>Provides pre-built request/response objects for common scenarios:
 * single revise, batch finalize, partial outcomes, mixed-batch rejection,
 * and constraint variations.
 */
public final class NegotiationFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NegotiationFixtures() {}

    public static String randomIdempotencyKey() {
        return "idem-" + UUID.randomUUID().toString().replace("-", "");
    }

    // -- Proposals --

    public static ObjectNode draftProposal(String proposalId, String parentProposalId) {
        ObjectNode proposal = MAPPER.createObjectNode();
        proposal.put("proposal_id", proposalId);
        proposal.put("parent_proposal_id", parentProposalId);
        proposal.put("proposal_status", "draft");
        proposal.put("name", "Test Plan " + proposalId);

        ObjectNode terms = MAPPER.createObjectNode();
        terms.put("total_budget", 50000);
        terms.put("currency", "USD");
        proposal.set("commercial_terms", terms);
        proposal.put("terms_digest", TermsDigest.compute(terms));

        proposal.putArray("allocations").addObject()
                .put("product_id", "prod-1")
                .put("allocation_percentage", 100);
        return proposal;
    }

    public static ObjectNode committedProposal(String proposalId,
                                               String parentProposalId) {
        ObjectNode proposal = draftProposal(proposalId, parentProposalId);
        proposal.put("proposal_status", "committed");
        proposal.put("expires_at",
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(24).toString());
        return proposal;
    }

    // -- Requests --

    public static RefineProposalsRequest singleReviseRequest(String proposalId) {
        return RefineProposalsRequest.builder()
                .idempotencyKey(randomIdempotencyKey())
                .addRefinement(ProposalRefinement.revise(
                        proposalId, "Lower CPM to $8 and extend flight by 2 weeks"))
                .build();
    }

    public static RefineProposalsRequest batchFinalizeRequest(List<String> proposalIds) {
        var builder = RefineProposalsRequest.builder()
                .idempotencyKey(randomIdempotencyKey());
        for (String id : proposalIds) {
            builder.addRefinement(ProposalRefinement.finalize(id));
        }
        return builder.build();
    }

    // -- Constraints --

    public static CpmConstraint standardCpmCeiling() {
        return new CpmConstraint(new BigDecimal("12.50"), "USD");
    }

    public static ImpressionsConstraint minimumImpressions() {
        return new ImpressionsConstraint(100_000);
    }

    public static FlightConstraint q4Flight() {
        return new FlightConstraint(
                OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC));
    }

    // -- Response fragments --

    /**
     * Builds a JSON string for a completed refine_proposals response
     * with a single revised result.
     */
    public static String revisedResponseJson(String sourceProposalId,
                                             String newProposalId) {
        ObjectNode proposal = draftProposal(newProposalId, sourceProposalId);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("source_proposal_id", sourceProposalId);
        result.put("outcome", "revised");
        result.set("proposal", proposal);

        ObjectNode response = MAPPER.createObjectNode();
        response.put("status", "completed");
        response.putArray("results").add(result);
        response.putArray("products");

        return response.toString();
    }

    /**
     * Builds a JSON string for an "unable" result with a given reason.
     */
    public static String unableResponseJson(String sourceProposalId, String reason) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("source_proposal_id", sourceProposalId);
        result.put("outcome", "unable");
        result.put("reason", reason);

        ObjectNode response = MAPPER.createObjectNode();
        response.put("status", "completed");
        response.putArray("results").add(result);
        response.putArray("products");

        return response.toString();
    }
}
