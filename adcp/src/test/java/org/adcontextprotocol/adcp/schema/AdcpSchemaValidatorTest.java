package org.adcontextprotocol.adcp.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdcpSchemaValidator}.
 */
class AdcpSchemaValidatorTest {

    private final AdcpSchemaValidator validator = new AdcpSchemaValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validate_valid_pagination_request() throws Exception {
        JsonNode instance = mapper.readTree("""
                {
                    "max_results": 50,
                    "cursor": "abc123"
                }
                """);

        ValidationResult result = validator.validate(
                "schemas/3.0.11/core/pagination-request.json", instance);
        assertTrue(result.isValid(), "Valid pagination request should pass: " + result.summary());
    }

    @Test
    void validate_with_leading_slash_uri() throws Exception {
        JsonNode instance = mapper.readTree("""
                {
                    "max_results": 50
                }
                """);

        ValidationResult result = validator.validate(
                "/schemas/3.0.11/core/pagination-request.json", instance);
        assertTrue(result.isValid(), "Leading-slash URI should work: " + result.summary());
    }

    @Test
    void validate_invalid_instance_returns_errors() throws Exception {
        // pagination-request requires max_results to be an integer
        ObjectNode instance = mapper.createObjectNode();
        instance.put("max_results", "not_a_number");

        ValidationResult result = validator.validate(
                "schemas/3.0.11/core/pagination-request.json", instance);
        assertFalse(result.isValid(), "Invalid type should fail validation");
        assertFalse(result.errors().isEmpty());
        assertFalse(result.summary().equals("valid"));
    }

    @Test
    void validate_caches_schemas() throws Exception {
        JsonNode instance = mapper.createObjectNode();

        // First call loads the schema
        validator.validate("schemas/3.0.11/core/pagination-request.json", instance);
        // Second call should use cache — no exception means it works
        ValidationResult result = validator.validate(
                "schemas/3.0.11/core/pagination-request.json", instance);
        assertNotNull(result);
    }

    @Test
    void preload_fails_fast_on_missing_schema() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.preload("schemas/3.0.11/nonexistent.json"));
    }

    @Test
    void validate_missing_schema_throws() {
        JsonNode instance = mapper.createObjectNode();
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("schemas/3.0.11/does-not-exist.json", instance));
    }
}
