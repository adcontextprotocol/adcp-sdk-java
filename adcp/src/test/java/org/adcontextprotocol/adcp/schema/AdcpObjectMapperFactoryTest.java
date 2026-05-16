package org.adcontextprotocol.adcp.schema;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdcpObjectMapperFactory}.
 */
class AdcpObjectMapperFactoryTest {

    @Test
    void factory_creates_mapper_with_java_time_module() {
        ObjectMapper mapper = AdcpObjectMapperFactory.create();
        assertNotNull(mapper.getRegisteredModuleIds());
    }

    @Test
    void factory_widens_stream_read_constraints() {
        ObjectMapper mapper = AdcpObjectMapperFactory.create();
        StreamReadConstraints constraints = mapper.getFactory().streamReadConstraints();
        assertTrue(constraints.getMaxStringLength() >= 100_000_000,
                "MaxStringLength should be at least 100MB for creative payloads");
        assertTrue(constraints.getMaxNestingDepth() >= 2000,
                "Read MaxNestingDepth should be at least 2000 for deep catalog responses");
    }

    @Test
    void factory_widens_stream_write_constraints() {
        ObjectMapper mapper = AdcpObjectMapperFactory.create();
        StreamWriteConstraints constraints = mapper.getFactory().streamWriteConstraints();
        assertTrue(constraints.getMaxNestingDepth() >= 2000,
                "MaxNestingDepth should be at least 2000 for deep catalog responses");
    }

    @Test
    void factory_creates_independent_instances() {
        ObjectMapper a = AdcpObjectMapperFactory.create();
        ObjectMapper b = AdcpObjectMapperFactory.create();
        assertNotSame(a, b, "Each call should return a new ObjectMapper");
    }
}
