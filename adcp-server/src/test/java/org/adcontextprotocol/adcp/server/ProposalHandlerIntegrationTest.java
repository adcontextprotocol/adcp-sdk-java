package org.adcontextprotocol.adcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.adcontextprotocol.adcp.negotiation.ProposalRefinement;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsRequest;
import org.adcontextprotocol.adcp.negotiation.RefinementCapability;
import org.adcontextprotocol.adcp.negotiation.RefinementAction;
import org.adcontextprotocol.adcp.negotiation.RefinementResult;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsResponse;
import org.adcontextprotocol.adcp.schema.AdcpObjectMapperFactory;
import org.adcontextprotocol.adcp.server.negotiation.ProposalHandler;
import org.adcontextprotocol.adcp.server.negotiation.ProposalSuccessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProposalHandlerIntegrationTest {

    private final ObjectMapper mapper = AdcpObjectMapperFactory.create();

    @Test
    void revise_dispatches_only_after_preflight_and_validates_result() {
        RecordingHandler handler = new RecordingHandler();
        AdcpServerBuilder server = AdcpServerBuilder.create(new EmptyPlatform())
                .proposalHandler(handler);
        var request = RefineProposalsRequest.builder().idempotencyKey(key())
                .addRefinement(ProposalRefinement.revise("src-1", "lower price"))
                .build();

        McpSchema.CallToolResult result = call(server, request);

        assertFalse(result.isError());
        assertTrue(handler.preflightCalled);
        assertEquals(1, handler.refineCalls);
        assertEquals(0, handler.finalizeCalls);
    }

    @Test
    void finalize_uses_explicit_atomic_callback() {
        RecordingHandler handler = new RecordingHandler();
        AdcpServerBuilder server = AdcpServerBuilder.create(new EmptyPlatform())
                .proposalHandler(handler);
        var request = RefineProposalsRequest.builder().idempotencyKey(key())
                .addRefinement(ProposalRefinement.finalize("src-1"))
                .build();

        McpSchema.CallToolResult result = call(server, request);

        assertFalse(result.isError());
        assertEquals(1, handler.refineCalls);
        assertEquals(1, handler.finalizeCalls);
    }

    @Test
    void preflight_failure_prevents_mutation() {
        RecordingHandler handler = new RecordingHandler();
        handler.preflightError = "source does not belong to this buyer";
        AdcpServerBuilder server = AdcpServerBuilder.create(new EmptyPlatform())
                .proposalHandler(handler);
        var request = RefineProposalsRequest.builder().idempotencyKey(key())
                .addRefinement(ProposalRefinement.revise("src-1", "change"))
                .build();

        McpSchema.CallToolResult result = call(server, request);

        assertTrue(result.isError());
        assertEquals(0, handler.refineCalls + handler.finalizeCalls);
    }

    @Test
    void invalid_finalize_result_fails_before_atomic_commit() {
        boolean[] committed = {false};
        ProposalHandler handler = new ProposalHandler() {
            @Override
            public RefinementCapability capability() {
                return new RefinementCapability(Set.of(), null);
            }

            @Override
            public List<RefinementResult> refine(List<ProposalRefinement> refinements,
                                                 String key, AdcpContext context) {
                ObjectNode invalid = mapper.createObjectNode();
                invalid.put("proposal_id", refinements.getFirst().proposalId());
                invalid.put("parent_proposal_id", refinements.getFirst().proposalId());
                invalid.put("proposal_status", "committed");
                invalid.put("expires_at", "2099-01-01T00:00:00Z");
                invalid.set("commercial_terms", mapper.createObjectNode().put("price", 10));
                invalid.put("terms_digest", "sha256:invalid");
                return List.of(new RefinementResult.Finalized(
                        refinements.getFirst().proposalId(), invalid));
            }

            @Override
            public RefineProposalsResponse finalizeAtomically(
                    List<ProposalRefinement> refinements, String key, AdcpContext context,
                    java.util.function.Supplier<RefineProposalsResponse> operation) {
                RefineProposalsResponse results = operation.get();
                committed[0] = true;
                return results;
            }
        };
        AdcpServerBuilder server = AdcpServerBuilder.create(new EmptyPlatform())
                .proposalHandler(handler);
        var request = RefineProposalsRequest.builder().idempotencyKey(key())
                .addRefinement(ProposalRefinement.finalize("src-1"))
                .build();

        assertTrue(call(server, request).isError());
        assertFalse(committed[0]);
    }

    @SuppressWarnings("unchecked")
    private McpSchema.CallToolResult call(AdcpServerBuilder server,
                                          RefineProposalsRequest request) {
        Map<String, Object> args = mapper.convertValue(request, Map.class);
        return server.handleToolCall(mapper, "refine_proposals",
                new McpSchema.CallToolRequest("refine_proposals", args));
    }

    private static String key() {
        return "idem-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static final class EmptyPlatform extends AdcpPlatform {}

    private final class RecordingHandler implements ProposalHandler {
        boolean preflightCalled;
        String preflightError;
        int refineCalls;
        int finalizeCalls;

        @Override
        public RefinementCapability capability() {
            return new RefinementCapability(Set.of(), null);
        }

        @Override
        public String preflight(List<ProposalRefinement> refinements,
                                String idempotencyKey, AdcpContext ctx) {
            preflightCalled = true;
            return preflightError;
        }

        @Override
        public List<RefinementResult> refine(List<ProposalRefinement> refinements,
                                             String idempotencyKey, AdcpContext ctx) {
            refineCalls++;
            ObjectNode draft = mapper.createObjectNode();
            draft.set("commercial_terms", mapper.createObjectNode().put("price", 10));
            if (refinements.getFirst().action() == RefinementAction.FINALIZE) {
                ProposalSuccessor.stampFinalized(
                        draft, refinements.getFirst().proposalId(), "2099-01-01T00:00:00Z");
                return List.of(new RefinementResult.Finalized(
                        refinements.getFirst().proposalId(), draft));
            }
            ProposalSuccessor.stamp(draft, refinements.getFirst().proposalId());
            return List.of(new RefinementResult.Revised(
                    refinements.getFirst().proposalId(), List.of(draft), null));
        }

        @Override
        public RefineProposalsResponse finalizeAtomically(
                List<ProposalRefinement> refinements,
                String idempotencyKey, AdcpContext ctx,
                java.util.function.Supplier<RefineProposalsResponse> operation) {
            finalizeCalls++;
            return operation.get();
        }
    }
}
