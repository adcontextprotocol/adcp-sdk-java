package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/**
 * Flight timing constraint, checked against the envelope's
 * {@code start_time}/{@code end_time}. An "asap" start never
 * satisfies a {@code startNoLaterThan} bound.
 *
 * @param startNoLaterThan campaign must start on or before this time
 * @param endNoEarlierThan campaign must end on or after this time
 */
public record FlightConstraint(
        @Nullable @JsonProperty("start_no_later_than") OffsetDateTime startNoLaterThan,
        @Nullable @JsonProperty("end_no_earlier_than") OffsetDateTime endNoEarlierThan) {

    public FlightConstraint {
        if (startNoLaterThan == null && endNoEarlierThan == null) {
            throw new IllegalArgumentException(
                    "flight constraint must specify at least one bound");
        }
    }
}
