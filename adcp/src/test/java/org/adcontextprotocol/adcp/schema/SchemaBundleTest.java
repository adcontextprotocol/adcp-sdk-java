package org.adcontextprotocol.adcp.schema;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SchemaBundle}.
 */
class SchemaBundleTest {

    @Test
    void load_returns_parsed_schema() {
        JsonNode schema = SchemaBundle.load("3.0.11/core/pagination-request.json");
        assertNotNull(schema);
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.has("properties"), "Schema should have properties");
    }

    @Test
    void load_throws_on_missing_schema() {
        assertThrows(IllegalArgumentException.class,
                () -> SchemaBundle.load("3.0.11/nonexistent.json"));
    }

    @Test
    void loadIndex_returns_master_index() {
        JsonNode index = SchemaBundle.loadIndex("3.0.11");
        assertNotNull(index);
        // The index should contain task definitions
        assertTrue(index.size() > 0, "Index should not be empty");
    }

    @Test
    void exists_returns_true_for_known_schema() {
        assertTrue(SchemaBundle.exists("3.0.11/core/pagination-request.json"));
    }

    @Test
    void exists_returns_false_for_missing_schema() {
        assertFalse(SchemaBundle.exists("3.0.11/nonexistent.json"));
    }

    @Test
    void load_schema_with_refs() {
        // Test loading a schema that contains $ref pointers
        JsonNode schema = SchemaBundle.load("3.0.11/core/format.json");
        assertNotNull(schema);
        assertEquals("Format", schema.path("title").asText());
        assertTrue(schema.has("$defs"), "format.json should have $defs");
    }

    @Test
    void load_throws_on_null_path() {
        assertThrows(IllegalArgumentException.class, () -> SchemaBundle.load(null));
    }

    @Test
    void load_throws_on_path_traversal() {
        assertThrows(IllegalArgumentException.class, () -> SchemaBundle.load("../secret.json"));
    }

    @Test
    void load_throws_on_leading_slash() {
        assertThrows(IllegalArgumentException.class, () -> SchemaBundle.load("/3.0.11/core/format.json"));
    }

    @Test
    void exists_returns_false_for_null_path() {
        assertFalse(SchemaBundle.exists(null));
    }

    @Test
    void exists_returns_false_for_path_traversal() {
        assertFalse(SchemaBundle.exists("../secret.json"));
    }

    @Test
    void exists_returns_false_for_leading_slash() {
        assertFalse(SchemaBundle.exists("/3.0.11/core/format.json"));
    }
}
