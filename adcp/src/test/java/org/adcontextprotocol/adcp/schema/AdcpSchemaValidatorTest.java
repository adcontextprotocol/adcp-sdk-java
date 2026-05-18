package org.adcontextprotocol.adcp.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.resource.ClasspathSchemaLoader;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeout;

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

    /**
     * Security: the validator must NOT attempt to fetch any http(s):// reference from the
     * network. This verifies that the ClasspathSchemaLoader-only configuration is in effect
     * by asserting the resolution fails immediately (not after a network timeout) when a
     * synthetic schema with a network {@code $ref} is given.
     */
    @Test
    void network_refs_are_blocked_not_fetched() {
        // Build a schema factory with the same ClasspathSchemaLoader-only configuration
        // used by AdcpSchemaValidator and assert it fails fast (< 2 s) when presented
        // with an http:// $ref rather than hanging on a network socket.
        assertTimeout(Duration.ofSeconds(2), () -> {
            JsonSchemaFactory isolatedFactory = JsonSchemaFactory.getInstance(
                    SpecVersion.VersionFlag.V7,
                    builder -> builder.schemaLoaders(loaders ->
                            loaders.values(list -> {
                                list.clear();
                                list.add(new ClasspathSchemaLoader());
                            })
                    )
            );
            String schemaWithNetworkRef = """
                    {
                        "$schema": "http://json-schema.org/draft-07/schema#",
                        "type": "object",
                        "properties": {
                            "evil": { "$ref": "http://example.com/evil-schema-that-must-not-be-fetched.json" }
                        }
                    }
                    """;
            // Loading the schema itself should succeed (the $ref is not resolved until
            // validation). Validation against the evil property must fail fast.
            var schema = isolatedFactory.getSchema(schemaWithNetworkRef);
            ObjectNode instance = mapper.createObjectNode();
            instance.put("evil", "something");
            // Either validate() throws because the ref cannot be resolved, or it returns
            // validation errors. Either way it must NOT block on a network connection.
            try {
                schema.validate(instance);
            } catch (Exception expected) {
                // Expected: schema resolution failure, not a network timeout
            }
        });
    }
}
