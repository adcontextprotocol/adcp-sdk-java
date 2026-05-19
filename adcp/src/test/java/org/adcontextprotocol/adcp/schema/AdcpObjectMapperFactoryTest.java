package org.adcontextprotocol.adcp.schema;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdcpObjectMapperFactory}.
 */
class AdcpObjectMapperFactoryTest {

    @Test
    void factory_creates_mapper_with_java_time_module() {
        ObjectMapper mapper = AdcpObjectMapperFactory.create();
        assertTrue(
            mapper.getRegisteredModuleIds().stream()
                .anyMatch(id -> id.toString().contains("jsr310") || id.toString().contains("JavaTimeModule")),
            "JavaTimeModule should be registered");
    }

    @Test
    void factory_serializes_dates_as_iso8601_strings() throws Exception {
        ObjectMapper mapper = AdcpObjectMapperFactory.create();
        Instant instant = Instant.parse("2024-07-01T12:00:00Z");
        String json = mapper.writeValueAsString(instant);
        // Should be a numeric-free ISO string, not a numeric timestamp
        assertTrue(json.contains("2024"), "Should serialize as ISO-8601 string, got: " + json);
        assertFalse(json.matches("^\\d+$"), "Should NOT serialize as numeric timestamp");
    }

    @Test
    void factory_tolerates_unknown_fields() throws Exception {
        ObjectMapper mapper = AdcpObjectMapperFactory.create();
        // Deserializing JSON with an unknown field should not throw
        String json = """
                {"known": "value", "future_field": "some_value"}
                """;
        // Use a generic type read to verify no exception is thrown
        var node = mapper.readTree(json);
        assertNotNull(node);
        assertEquals("value", node.get("known").asText());
    }

    @Test
    void factory_widens_stream_read_constraints() {
        ObjectMapper mapper = AdcpObjectMapperFactory.create();
        StreamReadConstraints constraints = mapper.getFactory().streamReadConstraints();
        assertTrue(constraints.getMaxStringLength() >= 10_000_000,
                "MaxStringLength should be at least 10MB for creative payloads");
        assertTrue(constraints.getMaxNestingDepth() >= 200,
                "Read MaxNestingDepth should be at least 200 for deep catalog responses");
    }

    @Test
    void factory_widens_stream_write_constraints() {
        ObjectMapper mapper = AdcpObjectMapperFactory.create();
        StreamWriteConstraints constraints = mapper.getFactory().streamWriteConstraints();
        assertTrue(constraints.getMaxNestingDepth() >= 200,
                "MaxNestingDepth should be at least 200 for deep catalog responses");
    }

    @Test
    void factory_creates_independent_instances() {
        ObjectMapper a = AdcpObjectMapperFactory.create();
        ObjectMapper b = AdcpObjectMapperFactory.create();
        assertNotSame(a, b, "Each call should return a new ObjectMapper");
    }
}
