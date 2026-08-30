package org.adcontextprotocol.adcp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import org.adcontextprotocol.adcp.AdcpClient;
import org.adcontextprotocol.adcp.AgentConfig;
import org.adcontextprotocol.adcp.http.SsrfPolicy;
import org.adcontextprotocol.adcp.negotiation.ProposalRefinement;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsRequest;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsResponse;
import org.adcontextprotocol.adcp.negotiation.RefinementConstraints;
import org.adcontextprotocol.adcp.negotiation.ResponseVerifier;
import org.adcontextprotocol.adcp.negotiation.TotalBudgetConstraint;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point for the {@code adcp} CLI and runnable proposal-negotiation lab.
 */
public final class Main {

    private Main() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        if (args.length > 0 && "proposal-negotiation".equals(args[0])) {
            runProposalNegotiation(args);
            return;
        }
        System.out.println("adcp proposal-negotiation [seller-mcp-url]");
        System.out.println("Set ADCP_AUTH_TOKEN before running the public training-seller lab.");
    }

    private static void runProposalNegotiation(String[] args) {
        String endpoint = args.length > 1 ? args[1]
                : "https://test-agent.adcontextprotocol.org/sales/profiles/constrained-seller/mcp";
        String token = System.getenv("ADCP_AUTH_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Set ADCP_AUTH_TOKEN to the public test token");
        }

        String nonce = UUID.randomUUID().toString();
        AgentConfig agent = AgentConfig.mcp("training-seller", URI.create(endpoint), token);
        try (AdcpClient client = AdcpClient.builder()
                .agent(agent)
                .ssrfPolicy(SsrfPolicy.strict())
                .build()) {
            Map<String, Object> version = Map.of(
                    "adcp_version", "3.2-beta.6", "adcp_major_version", 3);
            JsonNode capabilities = client.callTool(
                    "get_adcp_capabilities", version, JsonNode.class);
            if (!capabilities.path("media_buy").path("lifecycle_tools")
                    .toString().contains("refine_proposals")) {
                throw new IllegalStateException("seller did not advertise refine_proposals");
            }

            Map<String, Object> proposalArgs = new java.util.LinkedHashMap<>(version);
            proposalArgs.put("idempotency_key", "java-request-" + nonce);
            proposalArgs.put("brand", Map.of("domain", "acmeoutdoor.example"));
            proposalArgs.put("brief", "social engagement display");
            JsonNode proposals = client.callTool(
                    "request_proposals", proposalArgs, JsonNode.class);
            String sourceId = proposals.path("proposals").path(0)
                    .path("proposal_id").asText(null);
            if (sourceId == null) {
                throw new IllegalStateException("seller returned no source proposal");
            }

            RefinementConstraints constraints = new RefinementConstraints(
                    new TotalBudgetConstraint(null, new BigDecimal("50000"), "USD"),
                    null, null, null);
            RefineProposalsRequest three = request(
                    sourceId, constraints, 3, "java-refine-three-" + nonce);
            RefineProposalsResponse partial = client.refineProposals(three);
            requireVerified(three, partial);

            // This changes the logical request, so it deliberately uses a new key.
            RefineProposalsRequest two = request(
                    sourceId, constraints, 2, "java-refine-two-" + nonce);
            RefineProposalsResponse revised = client.refineProposals(two);
            requireVerified(two, revised);

            System.out.printf("first=%s (%d alternatives), retry=%s (%d alternatives)%n",
                    partial.results().getFirst().outcome(), proposalCount(partial),
                    revised.results().getFirst().outcome(), proposalCount(revised));
        }
    }

    private static RefineProposalsRequest request(
            String sourceId, RefinementConstraints constraints, int alternatives, String key) {
        return RefineProposalsRequest.builder()
                .adcpVersion("3.2-beta.6")
                .adcpMajorVersion(3)
                .idempotencyKey(key)
                .maxAlternatives(3)
                .addRefinement(ProposalRefinement.builder(sourceId)
                        .constraints(constraints)
                        .alternatives(alternatives)
                        .build())
                .build();
    }

    private static void requireVerified(RefineProposalsRequest request,
                                        RefineProposalsResponse response) {
        List<String> violations = ResponseVerifier.verify(request, response);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("invalid seller response: " + violations);
        }
    }

    private static int proposalCount(RefineProposalsResponse response) {
        return switch (response.results().getFirst()) {
            case org.adcontextprotocol.adcp.negotiation.RefinementResult.Revised r ->
                    r.proposals().size();
            case org.adcontextprotocol.adcp.negotiation.RefinementResult.Partial p ->
                    p.proposals().size();
            default -> 0;
        };
    }
}
