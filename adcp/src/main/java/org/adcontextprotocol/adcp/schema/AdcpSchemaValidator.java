package org.adcontextprotocol.adcp.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe schema validator that lazy-loads and caches JSON Schema
 * validators per schema URI. Delegates to {@code com.networknt:json-schema-validator}.
 *
 * <p>Schemas are loaded from the classpath (bundled in the SDK JAR). The first
 * validation call for a given schema URI pays the load cost; subsequent calls
 * use the cached validator.
 *
 * <p>Schema URIs follow the pattern {@code "/schemas/3.0.11/core/brand-ref.json"}.
 * The leading slash is stripped to form the classpath resource path
 * {@code "schemas/3.0.11/core/brand-ref.json"}.
 */
public final class AdcpSchemaValidator {

    private final ConcurrentMap<String, JsonSchema> cache = new ConcurrentHashMap<>();
    private final JsonSchemaFactory factory;

    public AdcpSchemaValidator() {
        this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }

    /**
     * Validates a JSON instance against the schema identified by {@code schemaUri}.
     *
     * @param schemaUri URI identifying the schema (e.g., {@code "/schemas/3.0.11/core/brand-ref.json"})
     * @param instance  the JSON node to validate
     * @return validation result containing any errors
     */
    public ValidationResult validate(String schemaUri, JsonNode instance) {
        JsonSchema schema = cache.computeIfAbsent(schemaUri, this::loadSchema);
        Set<ValidationMessage> errors = schema.validate(instance);
        return new ValidationResult(errors);
    }

    /**
     * Pre-loads a schema into the cache. Useful for fail-fast validation
     * of schema availability at startup.
     *
     * @param schemaUri URI identifying the schema
     * @throws IllegalArgumentException if the schema is not found on the classpath
     */
    public void preload(String schemaUri) {
        cache.computeIfAbsent(schemaUri, this::loadSchema);
    }

    private JsonSchema loadSchema(String uri) {
        // Normalize: strip leading slash to form classpath resource path.
        // Input: "/schemas/3.0.11/core/brand-ref.json" → "schemas/3.0.11/core/brand-ref.json"
        String resourcePath = uri.startsWith("/") ? uri.substring(1) : uri;

        InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalArgumentException("Schema not found on classpath: " + resourcePath);
        }

        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .build();
        return factory.getSchema(stream, config);
    }
}
