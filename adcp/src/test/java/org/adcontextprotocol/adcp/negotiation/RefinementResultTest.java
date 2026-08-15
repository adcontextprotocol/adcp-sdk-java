package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RefinementResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sealed_interface_permits_four_outcomes() {
        assertTrue(RefinementResult.class.isSealed());

        Class<?>[] permitted = RefinementResult.class.getPermittedSubclasses();
        assertNotNull(permitted);
        assertEquals(4, permitted.length);
    }

    @Test
    void revised_round_trips_via_jackson() throws Exception {
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("proposal_id", "p-new");
        proposal.put("proposal_status", "draft");

        String json = """
                {
                    "source_proposal_id": "p-1",
                    "outcome": "revised",
                    "proposal": {"proposal_id": "p-new", "proposal_status": "draft"}
                }
                """;

        RefinementResult result = mapper.readValue(json, RefinementResult.class);
        assertInstanceOf(RefinementResult.Revised.class, result);

        RefinementResult.Revised revised = (RefinementResult.Revised) result;
        assertEquals("p-1", revised.sourceProposalId());
        assertEquals(RefinementOutcome.REVISED, revised.outcome());
        assertEquals("p-new", revised.proposal().get("proposal_id").asText());
    }

    @Test
    void partial_round_trips_with_unsatisfied_constraints() throws Exception {
        String json = """
                {
                    "source_proposal_id": "p-2",
                    "outcome": "partial",
                    "proposal": {"proposal_id": "p-new", "proposal_status": "draft"},
                    "notes": "CPM constraint could not be fully met",
                    "unsatisfied_constraints": ["cpm"]
                }
                """;

        RefinementResult result = mapper.readValue(json, RefinementResult.class);
        assertInstanceOf(RefinementResult.Partial.class, result);

        RefinementResult.Partial partial = (RefinementResult.Partial) result;
        assertEquals("CPM constraint could not be fully met", partial.notes());
        assertEquals(List.of("cpm"), partial.unsatisfiedConstraints());
    }

    @Test
    void finalized_round_trips() throws Exception {
        String json = """
                {
                    "source_proposal_id": "p-3",
                    "outcome": "finalized",
                    "proposal": {
                        "proposal_id": "p-committed",
                        "proposal_status": "committed",
                        "expires_at": "2026-10-01T00:00:00Z"
                    }
                }
                """;

        RefinementResult result = mapper.readValue(json, RefinementResult.class);
        assertInstanceOf(RefinementResult.Finalized.class, result);
        assertEquals(RefinementOutcome.FINALIZED, result.outcome());
    }

    @Test
    void unable_round_trips_with_reason() throws Exception {
        String json = """
                {
                    "source_proposal_id": "p-4",
                    "outcome": "unable",
                    "reason": "hold_unavailable",
                    "notes": "Inventory no longer available for this flight"
                }
                """;

        RefinementResult result = mapper.readValue(json, RefinementResult.class);
        assertInstanceOf(RefinementResult.Unable.class, result);

        RefinementResult.Unable unable = (RefinementResult.Unable) result;
        assertEquals("hold_unavailable", unable.reason());
        assertNotNull(unable.notes());
    }

    @Test
    void pattern_matching_exhaustiveness() throws Exception {
        String json = """
                {
                    "source_proposal_id": "p-5",
                    "outcome": "unable",
                    "reason": "commercially_declined"
                }
                """;

        RefinementResult result = mapper.readValue(json, RefinementResult.class);

        String label = switch (result) {
            case RefinementResult.Revised r -> "revised: " + r.sourceProposalId();
            case RefinementResult.Partial p -> "partial: " + p.notes();
            case RefinementResult.Finalized f -> "finalized: " + f.sourceProposalId();
            case RefinementResult.Unable u -> "unable: " + u.reason();
        };

        assertEquals("unable: commercially_declined", label);
    }
}
