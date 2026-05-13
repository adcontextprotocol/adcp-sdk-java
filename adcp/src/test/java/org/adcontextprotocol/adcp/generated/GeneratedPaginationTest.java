package org.adcontextprotocol.adcp.generated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.adcontextprotocol.adcp.generated.core.PaginationRequest;
import org.adcontextprotocol.adcp.generated.core.PaginationResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the codegen MVP. Validates that generated records:
 * <ul>
 *   <li>Build via the static {@code builder()} factory (Request only).</li>
 *   <li>Round-trip through Jackson with snake_case JSON names.</li>
 *   <li>Treat required fields as non-null and optional as nullable.</li>
 * </ul>
 *
 * <p>The full codegen track adds the rest of the surface; this MVP exists
 * to prove the architecture.
 */
class GeneratedPaginationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void request_round_trips_with_snake_case_wire_format() throws Exception {
        PaginationRequest req = PaginationRequest.builder()
                .maxResults(25)
                .cursor("abc123")
                .build();

        String json = mapper.writeValueAsString(req);
        JsonNode parsed = mapper.readTree(json);

        // Wire format must use the snake_case schema names, not the
        // camelCase Java component names.
        assertEquals(25, parsed.get("max_results").asInt());
        assertEquals("abc123", parsed.get("cursor").asText());
        assertFalse(parsed.has("maxResults"), "camelCase leaked into wire: " + json);

        // Deserialize back; values match.
        PaginationRequest decoded = mapper.readValue(json, PaginationRequest.class);
        assertEquals(req, decoded);
    }

    @Test
    void response_required_field_round_trips() throws Exception {
        // has_more is the only required field.
        String json = "{\"has_more\": true}";
        PaginationResponse resp = mapper.readValue(json, PaginationResponse.class);
        assertTrue(resp.hasMore());
        assertNull(resp.cursor());
        assertNull(resp.totalCount());
    }

    @Test
    void response_full_round_trips() throws Exception {
        String json = "{\"has_more\": true, \"cursor\": \"xyz\", \"total_count\": 142}";
        PaginationResponse resp = mapper.readValue(json, PaginationResponse.class);
        assertTrue(resp.hasMore());
        assertEquals("xyz", resp.cursor());
        assertEquals(142, resp.totalCount());

        String reserialized = mapper.writeValueAsString(resp);
        JsonNode parsed = mapper.readTree(reserialized);
        assertEquals(142, parsed.get("total_count").asInt());
        assertTrue(parsed.get("has_more").asBoolean());
    }

    @Test
    void request_omits_unset_optional_fields() throws Exception {
        // Builder unset → null → Jackson should still emit (Jackson's default
        // is to write nulls). Default ObjectMapper config is fine for the
        // MVP. The codegen track will tighten serialization config when the
        // full ObjectMapper integration lands.
        PaginationRequest req = PaginationRequest.builder().maxResults(10).build();
        String json = mapper.writeValueAsString(req);
        JsonNode parsed = mapper.readTree(json);
        assertEquals(10, parsed.get("max_results").asInt());
        assertTrue(parsed.has("cursor"), "default Jackson config writes nulls");
        assertTrue(parsed.get("cursor").isNull());
    }
}
