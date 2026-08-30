package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RefinementResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sealed_interface_permits_four_outcomes() {
        assertTrue(RefinementResult.class.isSealed());
        assertEquals(4, RefinementResult.class.getPermittedSubclasses().length);
    }

    @Test
    void revised_round_trips_plural_proposals() throws Exception {
        String json = """
                {"source_proposal_id":"p-1","outcome":"revised","proposals":[
                  {"proposal_id":"p-new","proposal_status":"draft"}]}
                """;
        RefinementResult result = mapper.readValue(json, RefinementResult.class);
        var revised = assertInstanceOf(RefinementResult.Revised.class, result);
        assertEquals("p-new", revised.proposals().getFirst().get("proposal_id").asText());
        assertEquals(RefinementOutcome.REVISED, revised.outcome());
    }

    @Test
    void partial_round_trips_machine_readable_failures() throws Exception {
        String json = """
                {"source_proposal_id":"p-2","outcome":"partial",
                 "proposals":[{"proposal_id":"p-new","proposal_status":"draft"}],
                 "reason_code":"constraint_unsatisfiable","reason":"CPM too high",
                 "unsatisfied_constraints":["cpm"],
                 "unsatisfied_product_changes":{"prod-2":"include"}}
                """;
        var partial = assertInstanceOf(RefinementResult.Partial.class,
                mapper.readValue(json, RefinementResult.class));
        assertEquals(ProposalRefinementReason.CONSTRAINT_UNSATISFIABLE, partial.reasonCode());
        assertEquals(List.of("cpm"), partial.unsatisfiedConstraints());
        assertEquals(Map.of("prod-2", "include"), partial.unsatisfiedProductChanges());
    }

    @Test
    void all_reason_codes_round_trip() throws Exception {
        for (ProposalRefinementReason reason : ProposalRefinementReason.values()) {
            String json = """
                    {"source_proposal_id":"p","outcome":"unable",
                     "reason_code":"%s","reason":"bounded reason"%s}
                    """.formatted(reason.toWire(),
                    reason == ProposalRefinementReason.CONSTRAINT_UNSATISFIABLE
                            ? ",\"unsatisfied_constraints\":[\"cpm\"]" : "");
            var unable = assertInstanceOf(RefinementResult.Unable.class,
                    mapper.readValue(json, RefinementResult.class));
            assertEquals(reason, unable.reasonCode());
        }
    }

    @Test
    void serialize_then_deserialize_round_trip() throws Exception {
        ObjectNode proposal = mapper.createObjectNode().put("proposal_id", "p-rt");
        RefinementResult original = new RefinementResult.Revised(
                "src-1", List.of(proposal), null);
        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"outcome\":\"revised\""));
        assertEquals("src-1", mapper.readValue(json, RefinementResult.class).sourceProposalId());
    }
}
