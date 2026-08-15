package org.adcontextprotocol.adcp.negotiation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ConstraintsTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules();

    @Test
    void cpm_constraint_requires_max_and_currency() {
        var cpm = new CpmConstraint(new BigDecimal("12.50"), "USD");
        assertEquals(new BigDecimal("12.50"), cpm.max());
        assertEquals("USD", cpm.currency());
    }

    @Test
    void cpm_constraint_rejects_null_max() {
        assertThrows(IllegalArgumentException.class,
                () -> new CpmConstraint(null, "USD"));
    }

    @Test
    void cpm_constraint_rejects_blank_currency() {
        assertThrows(IllegalArgumentException.class,
                () -> new CpmConstraint(BigDecimal.TEN, ""));
    }

    @Test
    void cpm_constraint_round_trips_via_jackson() throws Exception {
        var cpm = new CpmConstraint(new BigDecimal("8.25"), "EUR");
        String json = mapper.writeValueAsString(cpm);
        var back = mapper.readValue(json, CpmConstraint.class);

        assertEquals(cpm.max().compareTo(back.max()), 0);
        assertEquals(cpm.currency(), back.currency());
    }

    @Test
    void impressions_constraint_requires_non_negative_min() {
        var ic = new ImpressionsConstraint(100_000);
        assertEquals(100_000, ic.min());
    }

    @Test
    void impressions_constraint_rejects_negative() {
        assertThrows(IllegalArgumentException.class,
                () -> new ImpressionsConstraint(-1));
    }

    @Test
    void impressions_constraint_round_trips() throws Exception {
        var ic = new ImpressionsConstraint(500_000);
        String json = mapper.writeValueAsString(ic);
        var back = mapper.readValue(json, ImpressionsConstraint.class);

        assertEquals(ic.min(), back.min());
    }

    @Test
    void flight_constraint_requires_at_least_one_bound() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlightConstraint(null, null));
    }

    @Test
    void flight_constraint_accepts_start_only() {
        var start = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        var fc = new FlightConstraint(start, null);
        assertEquals(start, fc.startNoLaterThan());
        assertNull(fc.endNoEarlierThan());
    }

    @Test
    void flight_constraint_accepts_both_bounds() {
        var start = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        var end = OffsetDateTime.of(2026, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
        var fc = new FlightConstraint(start, end);

        assertEquals(start, fc.startNoLaterThan());
        assertEquals(end, fc.endNoEarlierThan());
    }

    @Test
    void flight_constraint_round_trips() throws Exception {
        var start = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        var fc = new FlightConstraint(start, null);
        String json = mapper.writeValueAsString(fc);
        var back = mapper.readValue(json, FlightConstraint.class);

        assertEquals(fc.startNoLaterThan(), back.startNoLaterThan());
    }
}
