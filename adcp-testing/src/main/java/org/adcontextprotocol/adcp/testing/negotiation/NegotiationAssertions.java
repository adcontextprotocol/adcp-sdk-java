package org.adcontextprotocol.adcp.testing.negotiation;

import org.adcontextprotocol.adcp.negotiation.RefineProposalsRequest;
import org.adcontextprotocol.adcp.negotiation.RefineProposalsResponse;
import org.adcontextprotocol.adcp.negotiation.RefinementOutcome;
import org.adcontextprotocol.adcp.negotiation.ResponseVerifier;

import java.time.Instant;
import java.util.List;

/** Reusable conformance assertions for proposal-negotiation implementations. */
public final class NegotiationAssertions {

    private NegotiationAssertions() {}

    /** Fails with all protocol violations, rather than stopping at the first. */
    public static void assertValid(RefineProposalsRequest request,
                                   RefineProposalsResponse response) {
        assertValid(request, response, Instant.now());
    }

    /** Time-controlled variant for deterministic committed-hold expiry tests. */
    public static void assertValid(RefineProposalsRequest request,
                                   RefineProposalsResponse response,
                                   Instant now) {
        List<String> violations = ResponseVerifier.verify(request, response, now);
        if (!violations.isEmpty()) {
            throw new AssertionError("Invalid refine_proposals response: "
                    + String.join("; ", violations));
        }
    }

    /** Asserts the ordered per-entry outcomes without conflating task errors. */
    public static void assertOutcomes(RefineProposalsResponse response,
                                      RefinementOutcome... expected) {
        if (response.results() == null) {
            throw new AssertionError("Response has no completed results");
        }
        List<RefinementOutcome> actual = response.results().stream()
                .map(result -> result.outcome()).toList();
        if (!actual.equals(List.of(expected))) {
            throw new AssertionError("Expected outcomes " + List.of(expected)
                    + " but got " + actual);
        }
    }

    /** Asserts an exact idempotent replay without conflating it with a new request. */
    public static void assertExactReplay(RefineProposalsResponse original,
                                         RefineProposalsResponse replay) {
        if (!Boolean.TRUE.equals(replay.replayed())) {
            throw new AssertionError("Replay response is missing replayed=true");
        }
        if (!java.util.Objects.equals(original.results(), replay.results())
                || !java.util.Objects.equals(original.products(), replay.products())) {
            throw new AssertionError("Idempotent replay changed protocol results");
        }
    }
}
