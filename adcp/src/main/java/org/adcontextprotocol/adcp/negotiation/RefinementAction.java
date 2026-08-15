package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Actions that can be taken on a proposal during refinement.
 *
 * <p>{@code REVISE} creates a new draft snapshot with changed commercial terms.
 * {@code FINALIZE} targets a draft and creates a committed snapshot without
 * changing terms, reserving inventory until expires_at.
 */
public enum RefinementAction {

    REVISE("revise"),
    FINALIZE("finalize");

    private final String wire;

    RefinementAction(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String toWire() {
        return wire;
    }

    public static RefinementAction fromWire(String value) {
        return switch (value) {
            case "revise" -> REVISE;
            case "finalize" -> FINALIZE;
            default -> throw new IllegalArgumentException("Unknown refinement action: " + value);
        };
    }
}
