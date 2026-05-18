package org.adcontextprotocol.adcp.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ResponseSchemaValidationError}.
 */
class ResponseSchemaValidationErrorTest {

    private final AdcpSchemaValidator validator = new AdcpSchemaValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fromResult_creates_error_with_violations() {
        ObjectNode instance = mapper.createObjectNode();
        instance.put("max_results", "not_a_number");

        ValidationResult result = validator.validate(
                "schemas/3.0.11/core/pagination-request.json", instance);
        assertFalse(result.isValid());

        ResponseSchemaValidationError error = ResponseSchemaValidationError.fromResult(
                "schemas/3.0.11/core/pagination-request.json", result);

        assertEquals("schemas/3.0.11/core/pagination-request.json", error.schemaUri());
        assertFalse(error.violations().isEmpty());
        assertEquals("response_schema", error.errorCategory());
        assertTrue(error.getMessage().contains("violation(s)"));
    }

    @Test
    void fromResult_throws_on_valid_result() {
        ObjectNode instance = mapper.createObjectNode();
        instance.put("max_results", 50);

        ValidationResult result = validator.validate(
                "schemas/3.0.11/core/pagination-request.json", instance);
        assertTrue(result.isValid());

        assertThrows(IllegalArgumentException.class,
                () -> ResponseSchemaValidationError.fromResult(
                        "schemas/3.0.11/core/pagination-request.json", result));
    }

    @Test
    void error_category_is_response_schema() {
        var error = new ResponseSchemaValidationError("test-schema.json",
                java.util.List.of(new ResponseSchemaValidationError.SchemaViolation(
                        "/field", "type mismatch", "type")));
        assertEquals("response_schema", error.errorCategory());
    }

    @Test
    void violations_are_immutable() {
        var violations = new java.util.ArrayList<ResponseSchemaValidationError.SchemaViolation>();
        violations.add(new ResponseSchemaValidationError.SchemaViolation("/a", "err", "type"));
        var error = new ResponseSchemaValidationError("s.json", violations);

        violations.add(new ResponseSchemaValidationError.SchemaViolation("/b", "err2", "required"));
        assertEquals(1, error.violations().size(), "violations should be defensively copied");
    }
}
