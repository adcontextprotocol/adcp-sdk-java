package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The kind of successor proposal to create when refining an accepted proposal.
 */
public enum ChangeKind {

    AMENDMENT("amendment"),
    CANCELLATION("cancellation");

    private final String wire;

    ChangeKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String toWire() {
        return wire;
    }

    public static ChangeKind fromWire(String value) {
        return switch (value) {
            case "amendment" -> AMENDMENT;
            case "cancellation" -> CANCELLATION;
            default -> throw new IllegalArgumentException("Unknown change kind: " + value);
        };
    }
}
