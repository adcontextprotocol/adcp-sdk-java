package org.adcontextprotocol.adcp.schema;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Factory for Jackson {@link ObjectMapper} instances configured for AdCP payloads.
 *
 * <p>AdCP payloads can include large creative assets (base64-encoded images,
 * HTML templates) and deeply nested catalog responses. The default Jackson
 * limits are too restrictive for these payloads, so this factory widens
 * {@link StreamReadConstraints} and {@link StreamWriteConstraints} to safe
 * but AdCP-compatible levels.
 *
 * <p>Each call to {@link #create()} returns a new, independent ObjectMapper.
 * Adopters who need different settings should build their own ObjectMapper
 * rather than mutating the one returned here.
 */
public final class AdcpObjectMapperFactory {

    private AdcpObjectMapperFactory() {}

    /** Maximum string length for AdCP payloads (10 MB). */
    private static final int MAX_STRING_LENGTH = 10_000_000;

    /** Maximum nesting depth for AdCP catalog responses. */
    private static final int MAX_NESTING_DEPTH = 200;

    /**
     * Creates a new {@link ObjectMapper} configured for AdCP payloads.
     *
     * <p>Configuration includes:
     * <ul>
     *   <li>Java Time module for ISO-8601 date handling</li>
     *   <li>ISO-8601 string serialization (not numeric timestamps)</li>
     *   <li>Tolerant of unknown properties (forward compatibility with newer protocol versions)</li>
     *   <li>Widened {@link StreamReadConstraints} for creative payloads (string length + nesting depth)</li>
     *   <li>Widened {@link StreamWriteConstraints#maxNestingDepth()} for deep catalogs</li>
     * </ul>
     */
    public static ObjectMapper create() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Intentionally NOT enabling FAIL_ON_UNKNOWN_PROPERTIES —
                // SDK must tolerate fields added in newer protocol versions.
                .build();
        // Defense-in-depth: disable default typing to prevent deserialization gadget attacks.
        // Jackson's default is off, but this makes it explicit and resilient to future config changes.
        mapper.deactivateDefaultTyping();

        // Widen stream constraints for AdCP creative payloads and deep catalogs
        mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxStringLength(MAX_STRING_LENGTH)
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .build());
        mapper.getFactory().setStreamWriteConstraints(
                StreamWriteConstraints.builder()
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .build());

        return mapper;
    }
}
