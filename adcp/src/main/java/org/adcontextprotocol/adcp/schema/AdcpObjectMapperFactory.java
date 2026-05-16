package org.adcontextprotocol.adcp.schema;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** Maximum string length for AdCP payloads (100 MB). */
    private static final int MAX_STRING_LENGTH = 100_000_000;

    /** Maximum nesting depth for AdCP catalog responses. */
    private static final int MAX_NESTING_DEPTH = 2000;

    /**
     * Creates a new {@link ObjectMapper} configured for AdCP payloads.
     *
     * <p>Configuration includes:
     * <ul>
     *   <li>Java Time module for ISO-8601 date handling</li>
     *   <li>Widened {@link StreamReadConstraints} for creative payloads (string length + nesting depth)</li>
     *   <li>Widened {@link StreamWriteConstraints#maxNestingDepth()} for deep catalogs</li>
     *   <li>Strict unknown-property handling to catch schema mismatches early</li>
     * </ul>
     */
    public static ObjectMapper create() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

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
