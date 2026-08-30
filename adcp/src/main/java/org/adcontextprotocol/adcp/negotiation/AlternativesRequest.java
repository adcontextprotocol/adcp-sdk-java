package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request for distinct draft alternatives. */
public record AlternativesRequest(@JsonProperty("count") int count) {

    public static final int PROTOCOL_MAX = 10;

    public AlternativesRequest {
        if (count < 2 || count > PROTOCOL_MAX) {
            throw new IllegalArgumentException("alternatives.count must be 2-" + PROTOCOL_MAX);
        }
    }
}
