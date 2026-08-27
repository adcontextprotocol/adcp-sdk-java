package org.adcontextprotocol.adcp.server.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.adcontextprotocol.adcp.negotiation.TermsDigest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProposalSuccessorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void stamp_sets_lineage_and_digest() {
        ObjectNode terms = mapper.createObjectNode().put("price", 42);
        ObjectNode draft = mapper.createObjectNode();
        draft.set("commercial_terms", terms);

        ProposalSuccessor.stamp(draft, "parent-123");

        assertEquals("parent-123", draft.get("parent_proposal_id").asText());
        assertEquals("draft", draft.get("proposal_status").asText());
        assertNotNull(draft.get("proposal_id"));
        assertTrue(TermsDigest.verify(draft.get("terms_digest").asText(), terms));
    }

    @Test
    void stamp_preserves_existing_proposal_id() {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("proposal_id", "keep-this");

        ProposalSuccessor.stamp(draft, "parent-1");

        assertEquals("keep-this", draft.get("proposal_id").asText());
    }

    @Test
    void stamp_finalized_sets_committed_status_and_expiry() {
        ObjectNode terms = mapper.createObjectNode().put("total", 10000);
        ObjectNode draft = mapper.createObjectNode();
        draft.set("commercial_terms", terms);

        ProposalSuccessor.stampFinalized(draft, "src-1", "2026-12-31T23:59:59Z");

        assertEquals("committed", draft.get("proposal_status").asText());
        assertEquals("2026-12-31T23:59:59Z", draft.get("expires_at").asText());
        assertEquals("src-1", draft.get("parent_proposal_id").asText());
    }

    @Test
    void rejects_null_source() {
        ObjectNode draft = mapper.createObjectNode();
        assertThrows(NullPointerException.class,
                () -> ProposalSuccessor.stamp(draft, null));
    }
}
