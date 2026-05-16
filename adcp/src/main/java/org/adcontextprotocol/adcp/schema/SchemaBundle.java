package org.adcontextprotocol.adcp.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Provides runtime access to the AdCP schema bundle bundled in the SDK JAR.
 *
 * <p>Schemas are stored under {@code schemas/} in the classpath. This class
 * provides convenience methods to load individual schemas or the master index.
 */
public final class SchemaBundle {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA_PREFIX = "schemas/";

    private SchemaBundle() {}

    /**
     * Loads a schema by its path relative to the schema bundle root.
     *
     * @param path schema path (e.g., {@code "3.0.11/core/brand-ref.json"})
     * @return the parsed JSON schema node
     * @throws IllegalArgumentException if the schema is not found on the classpath
     */
    public static JsonNode load(String path) {
        String resourcePath = SCHEMA_PREFIX + path;
        try (InputStream stream = SchemaBundle.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("Schema not found: " + resourcePath);
            }
            return MAPPER.readTree(stream);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read schema: " + resourcePath, e);
        }
    }

    /**
     * Loads the master index ({@code index.json}) for the given protocol version.
     *
     * @param version protocol version (e.g., {@code "3.0.11"})
     * @return the parsed index JSON node
     */
    public static JsonNode loadIndex(String version) {
        return load(version + "/index.json");
    }

    /**
     * Checks whether a schema exists on the classpath.
     *
     * @param path schema path relative to the bundle root
     * @return {@code true} if the schema resource exists
     */
    public static boolean exists(String path) {
        String resourcePath = SCHEMA_PREFIX + path;
        return SchemaBundle.class.getClassLoader().getResource(resourcePath) != null;
    }
}
