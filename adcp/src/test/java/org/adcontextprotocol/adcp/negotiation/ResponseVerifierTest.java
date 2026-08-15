package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResponseVerifierTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String key() {
        return "idem-" + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void valid_revised_response_passes_verification() throws Exception {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(key())
                .addRefinement(ProposalRefinement.revise("src-1", "lower CPM"))
                .build();

        ObjectNode proposal = draftProposal("new-1", "src-1");
        String json = """
                {
                    "status": "completed",
                    "results": [{
                        "source_proposal_id": "src-1",
                        "outcome": "revised",
                        "proposal": %s
                    }],
                    "products": []
                }
                """.formatted(proposal.toString());

        var response = mapper.readValue(json, RefineProposalsResponse.class);
        List<String> violations = ResponseVerifier.verify(request, response);

        assertTrue(violations.isEmpty(), "Expected no violations but got: " + violations);
    }

    @Test
    void detects_result_count_mismatch() throws Exception {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(key())
                .addRefinement(ProposalRefinement.revise("src-1", "test"))
                .addRefinement(ProposalRefinement.revise("src-2", "test"))
                .build();

        String json = """
                {
                    "status": "completed",
                    "results": [{
                        "source_proposal_id": "src-1",
                        "outcome": "unable",
                        "reason": "commercially_declined"
                    }],
                    "products": []
                }
                """;

        var response = mapper.readValue(json, RefineProposalsResponse.class);
        List<String> violations = ResponseVerifier.verify(request, response);

        assertTrue(violations.stream().anyMatch(v -> v.contains("result count")));
    }

    @Test
    void detects_ordering_mismatch() throws Exception {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(key())
                .addRefinement(ProposalRefinement.revise("src-1", "a"))
                .addRefinement(ProposalRefinement.revise("src-2", "b"))
                .build();

        String json = """
                {
                    "status": "completed",
                    "results": [
                        {"source_proposal_id": "src-2", "outcome": "unable", "reason": "test"},
                        {"source_proposal_id": "src-1", "outcome": "unable", "reason": "test"}
                    ],
                    "products": []
                }
                """;

        var response = mapper.readValue(json, RefineProposalsResponse.class);
        List<String> violations = ResponseVerifier.verify(request, response);

        assertTrue(violations.stream().anyMatch(v -> v.contains("does not match request")));
    }

    @Test
    void detects_mixed_finalize_in_response() throws Exception {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(key())
                .addRefinement(ProposalRefinement.finalize("src-1"))
                .addRefinement(ProposalRefinement.finalize("src-2"))
                .build();

        ObjectNode committed = committedProposal("new-1", "src-1");

        // Construct a response where one result is finalized but the other
        // is revised (violates atomicity)
        String json = """
                {
                    "status": "completed",
                    "results": [
                        {
                            "source_proposal_id": "src-1",
                            "outcome": "finalized",
                            "proposal": %s
                        },
                        {
                            "source_proposal_id": "src-2",
                            "outcome": "unable",
                            "reason": "hold_unavailable"
                        }
                    ],
                    "products": []
                }
                """.formatted(committed.toString());

        var response = mapper.readValue(json, RefineProposalsResponse.class);
        List<String> violations = ResponseVerifier.verify(request, response);

        assertTrue(violations.stream().anyMatch(v -> v.contains("finalize batches")));
    }

    @Test
    void detects_missing_lineage() throws Exception {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(key())
                .addRefinement(ProposalRefinement.revise("src-1", "test"))
                .build();

        // Proposal without parent_proposal_id
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("proposal_id", "new-1");
        proposal.put("proposal_status", "draft");

        String json = """
                {
                    "status": "completed",
                    "results": [{
                        "source_proposal_id": "src-1",
                        "outcome": "revised",
                        "proposal": %s
                    }],
                    "products": []
                }
                """.formatted(proposal.toString());

        var response = mapper.readValue(json, RefineProposalsResponse.class);
        List<String> violations = ResponseVerifier.verify(request, response);

        assertTrue(violations.stream()
                .anyMatch(v -> v.contains("parent_proposal_id")));
    }

    @Test
    void detects_wrong_proposal_status_for_finalized() throws Exception {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(key())
                .addRefinement(ProposalRefinement.finalize("src-1"))
                .build();

        // Return a "draft" instead of "committed" for a finalized outcome
        ObjectNode proposal = draftProposal("new-1", "src-1");
        proposal.put("expires_at", "2026-10-01T00:00:00Z");

        String json = """
                {
                    "status": "completed",
                    "results": [{
                        "source_proposal_id": "src-1",
                        "outcome": "finalized",
                        "proposal": %s
                    }],
                    "products": []
                }
                """.formatted(proposal.toString());

        var response = mapper.readValue(json, RefineProposalsResponse.class);
        List<String> violations = ResponseVerifier.verify(request, response);

        assertTrue(violations.stream()
                .anyMatch(v -> v.contains("proposal_status") && v.contains("committed")));
    }

    @Test
    void skips_verification_for_async_responses() throws Exception {
        var request = RefineProposalsRequest.builder()
                .idempotencyKey(key())
                .addRefinement(ProposalRefinement.revise("src-1", "test"))
                .build();

        String json = """
                {"status": "submitted", "task_id": "task-123"}
                """;

        var response = mapper.readValue(json, RefineProposalsResponse.class);
        List<String> violations = ResponseVerifier.verify(request, response);

        assertTrue(violations.isEmpty());
    }

    @Test
    void digest_verification_catches_mismatch() throws Exception {
        ObjectNode terms = mapper.createObjectNode();
        terms.put("price", 10);

        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("proposal_id", "p-1");
        proposal.put("parent_proposal_id", "src-1");
        proposal.put("proposal_status", "draft");
        proposal.set("commercial_terms", terms);
        proposal.put("terms_digest", "sha256:AAAA_wrong_digest");

        String json = """
                {
                    "status": "completed",
                    "results": [{
                        "source_proposal_id": "src-1",
                        "outcome": "revised",
                        "proposal": %s
                    }],
                    "products": []
                }
                """.formatted(proposal.toString());

        var response = mapper.readValue(json, RefineProposalsResponse.class);
        List<String> violations = ResponseVerifier.verifyDigests(response);

        assertFalse(violations.isEmpty());
        assertTrue(violations.get(0).contains("terms_digest"));
    }

    // -- helpers --

    private ObjectNode draftProposal(String id, String parentId) {
        ObjectNode p = mapper.createObjectNode();
        p.put("proposal_id", id);
        p.put("parent_proposal_id", parentId);
        p.put("proposal_status", "draft");
        return p;
    }

    private ObjectNode committedProposal(String id, String parentId) {
        ObjectNode p = draftProposal(id, parentId);
        p.put("proposal_status", "committed");
        p.put("expires_at", "2026-12-31T23:59:59Z");
        return p;
    }
}
