package org.adcontextprotocol.adcp.server.negotiation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.adcontextprotocol.adcp.negotiation.TermsDigest;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates immutable successor proposals with correct lineage and digest.
 *
 * <p>Every proposal produced by {@code refine_proposals} must carry
 * {@code parent_proposal_id} equal to the request's source, a fresh
 * {@code proposal_id}, and a {@code terms_digest} matching its
 * {@code commercial_terms}. This utility enforces those invariants.
 */
public final class ProposalSuccessor {

    private ProposalSuccessor() {}

    /**
     * Stamps a draft proposal node with immutable lineage fields.
     * Sets {@code proposal_id}, {@code parent_proposal_id}, {@code proposal_status},
     * and recomputes {@code terms_digest} from {@code commercial_terms}.
     *
     * @param draft           mutable proposal node to stamp
     * @param sourceProposalId the source proposal this was forked from
     * @return the same node, mutated, for chaining
     */
    public static ObjectNode stamp(ObjectNode draft, String sourceProposalId) {
        Objects.requireNonNull(draft, "draft is required");
        Objects.requireNonNull(sourceProposalId, "sourceProposalId is required");

        // A successor is immutable protocol state. Never preserve an ID supplied
        // by an application draft, since it may be the source proposal's ID.
        draft.put("proposal_id", UUID.randomUUID().toString());
        draft.put("parent_proposal_id", sourceProposalId);

        if (!draft.has("proposal_status")) {
            draft.put("proposal_status", "draft");
        }

        JsonNode terms = draft.get("commercial_terms");
        if (terms == null || !terms.isObject()) {
            throw new IllegalArgumentException("commercial_terms object is required");
        }
        draft.put("terms_digest", TermsDigest.compute(terms));

        return draft;
    }

    /**
     * Stamps a committed (finalized) proposal. Sets status to "committed"
     * and requires {@code expires_at}.
     */
    public static ObjectNode stampFinalized(ObjectNode draft, String sourceProposalId,
                                            String expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt is required for finalized proposals");
        try {
            OffsetDateTime.parse(expiresAt);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiresAt must be an RFC 3339 timestamp", e);
        }
        stamp(draft, sourceProposalId);
        draft.put("proposal_status", "committed");
        draft.put("expires_at", expiresAt);
        return draft;
    }
}
