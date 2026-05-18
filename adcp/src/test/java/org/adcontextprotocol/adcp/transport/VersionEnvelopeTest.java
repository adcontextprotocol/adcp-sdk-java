package org.adcontextprotocol.adcp.transport;

import org.adcontextprotocol.adcp.AdcpVersion;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VersionEnvelope}.
 */
class VersionEnvelopeTest {

    @Test
    void build_default_version() {
        Map<String, Object> envelope = VersionEnvelope.build(null);

        assertEquals(3, envelope.get("adcp_major_version"));
        assertFalse(envelope.containsKey("adcp_version"));
    }

    @Test
    void build_v3_explicit() {
        Map<String, Object> envelope = VersionEnvelope.build(AdcpVersion.V3);

        assertEquals(3, envelope.get("adcp_major_version"));
        assertFalse(envelope.containsKey("adcp_version"));
    }

    @Test
    void build_v3_1() {
        Map<String, Object> envelope = VersionEnvelope.build(AdcpVersion.V3_1);

        assertEquals(3, envelope.get("adcp_major_version"));
        assertEquals("3.1", envelope.get("adcp_version"));
    }

    @Test
    void mergeInto_caller_args_win() {
        Map<String, Object> callerArgs = new LinkedHashMap<>();
        callerArgs.put("adcp_major_version", 99);
        callerArgs.put("my_param", "value");

        Map<String, Object> merged = VersionEnvelope.mergeInto(callerArgs, AdcpVersion.V3);

        // Caller's override wins
        assertEquals(99, merged.get("adcp_major_version"));
        // Caller's own param preserved
        assertEquals("value", merged.get("my_param"));
    }

    @Test
    void mergeInto_injects_version_when_caller_doesnt_set() {
        Map<String, Object> callerArgs = Map.of("param", "val");

        Map<String, Object> merged = VersionEnvelope.mergeInto(callerArgs, AdcpVersion.V3);

        assertEquals(3, merged.get("adcp_major_version"));
        assertEquals("val", merged.get("param"));
    }
}
