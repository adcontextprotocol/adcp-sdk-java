package org.adcontextprotocol.adcp.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.adcontextprotocol.adcp.negotiation.ProposalRefinementReason;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsResponse;
import org.adcontextprotocol.adcp.negotiation.RefinementOutcome;
import org.adcontextprotocol.adcp.schema.AdcpObjectMapperFactory;
import org.adcontextprotocol.adcp.testing.negotiation.NegotiationAssertions;
import org.adcontextprotocol.adcp.testing.negotiation.NegotiationFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NegotiationFixturesTest {

    private final ObjectMapper mapper = AdcpObjectMapperFactory.create();

    @Test
    void fixtures_cover_all_outcomes_and_reason_codes() throws Exception {
        NegotiationAssertions.assertOutcomes(read(
                        NegotiationFixtures.revisedResponseJson("source", "draft")),
                RefinementOutcome.REVISED);
        NegotiationAssertions.assertOutcomes(read(
                        NegotiationFixtures.partialResponseJson(
                                "source", "draft", "alternatives_unavailable")),
                RefinementOutcome.PARTIAL);
        NegotiationAssertions.assertOutcomes(read(
                        NegotiationFixtures.finalizedResponseJson("source", "committed")),
                RefinementOutcome.FINALIZED);
        for (ProposalRefinementReason reason : ProposalRefinementReason.values()) {
            NegotiationAssertions.assertOutcomes(read(
                            NegotiationFixtures.unableResponseJson("source", reason.toWire())),
                    RefinementOutcome.UNABLE);
        }
    }

    @Test
    void fixtures_cover_protocol_cardinality_boundaries() {
        assertEquals(25, NegotiationFixtures.maximumBatchRequest().refinements().size());
        assertEquals(10, NegotiationFixtures.maximumAlternativesRefinement("source")
                .alternatives().count());
    }

    private RefineProposalsResponse read(String json) throws Exception {
        return mapper.readValue(json, RefineProposalsResponse.class);
    }
}
