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
        assertEquals(new BigDecimal("100000"), ic.min());
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

    @Test
    void budget_constraint_requires_currency() {
        assertThrows(IllegalArgumentException.class,
                () -> new TotalBudgetConstraint(BigDecimal.TEN, null, null));
    }

    @Test
    void budget_constraint_requires_at_least_one_bound() {
        assertThrows(IllegalArgumentException.class,
                () -> new TotalBudgetConstraint(null, null, "USD"));
    }

    @Test
    void budget_constraint_rejects_min_exceeding_max() {
        assertThrows(IllegalArgumentException.class,
                () -> new TotalBudgetConstraint(
                        new BigDecimal("10000"), new BigDecimal("5000"), "USD"));
    }

    @Test
    void budget_constraint_accepts_valid_range() {
        var bc = new TotalBudgetConstraint(
                new BigDecimal("5000"), new BigDecimal("10000"), "USD");
        assertEquals(new BigDecimal("5000"), bc.min());
        assertEquals(new BigDecimal("10000"), bc.max());
        assertEquals("USD", bc.currency());
    }

    @Test
    void budget_constraint_accepts_max_only() {
        var bc = new TotalBudgetConstraint(null, new BigDecimal("50000"), "EUR");
        assertNull(bc.min());
        assertEquals(new BigDecimal("50000"), bc.max());
    }

    @Test
    void budget_constraint_round_trips() throws Exception {
        var bc = new TotalBudgetConstraint(
                new BigDecimal("1000"), new BigDecimal("5000"), "GBP");
        String json = mapper.writeValueAsString(bc);
        var back = mapper.readValue(json, TotalBudgetConstraint.class);

        assertEquals(0, bc.min().compareTo(back.min()));
        assertEquals(0, bc.max().compareTo(back.max()));
        assertEquals(bc.currency(), back.currency());
    }

    @Test
    void refinement_constraints_requires_at_least_one() {
        assertThrows(IllegalArgumentException.class,
                () -> new RefinementConstraints(null, null, null, null));
    }

    @Test
    void refinement_constraints_accepts_single_dimension() {
        var rc = new RefinementConstraints(
                new TotalBudgetConstraint(null, new BigDecimal("10000"), "USD"),
                null, null, null);
        assertNotNull(rc.totalBudget());
        assertNull(rc.cpm());
    }

    @Test
    void refinement_constraints_round_trips() throws Exception {
        var rc = new RefinementConstraints(
                new TotalBudgetConstraint(new BigDecimal("1000"), null, "USD"),
                new CpmConstraint(new BigDecimal("8.50"), "USD"),
                new ImpressionsConstraint(100_000),
                null);
        String json = mapper.writeValueAsString(rc);
        var back = mapper.readValue(json, RefinementConstraints.class);

        assertNotNull(back.totalBudget());
        assertNotNull(back.cpm());
        assertNotNull(back.impressions());
        assertNull(back.flight());
    }
}
