package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Possible outcomes for a single refinement entry in a response.
 *
 * <p>Precedence rule for reason codes: {@code constraint_unsatisfiable} wins
 * whenever a typed constraint failed; typed failures never surface as
 * {@code commercially_declined}.
 */
public enum RefinementOutcome {

    REVISED("revised"),
    PARTIAL("partial"),
    FINALIZED("finalized"),
    UNABLE("unable");

    private final String wire;

    RefinementOutcome(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String toWire() {
        return wire;
    }

    public static RefinementOutcome fromWire(String value) {
        return switch (value) {
            case "revised" -> REVISED;
            case "partial" -> PARTIAL;
            case "finalized" -> FINALIZED;
            case "unable" -> UNABLE;
            default -> throw new IllegalArgumentException("Unknown refinement outcome: " + value);
        };
    }
}
