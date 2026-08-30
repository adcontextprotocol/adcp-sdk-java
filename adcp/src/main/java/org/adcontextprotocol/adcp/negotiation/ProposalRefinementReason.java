package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Machine-readable reason for a partial or unable refinement result. */
public enum ProposalRefinementReason {
    COMMERCIALLY_DECLINED("commercially_declined"),
    CONSTRAINT_UNSATISFIABLE("constraint_unsatisfiable"),
    UNSUPPORTED_DIMENSION("unsupported_dimension"),
    UNINTERPRETED("uninterpreted"),
    ALTERNATIVES_UNAVAILABLE("alternatives_unavailable"),
    SOURCE_UNAVAILABLE("source_unavailable"),
    HOLD_UNAVAILABLE("hold_unavailable"),
    BATCH_ABORTED("batch_aborted");

    private final String wire;

    ProposalRefinementReason(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String toWire() {
        return wire;
    }

    @JsonCreator
    public static ProposalRefinementReason fromWire(String value) {
        for (ProposalRefinementReason reason : values()) {
            if (reason.wire.equals(value)) return reason;
        }
        throw new IllegalArgumentException("Unknown proposal refinement reason: " + value);
    }
}
