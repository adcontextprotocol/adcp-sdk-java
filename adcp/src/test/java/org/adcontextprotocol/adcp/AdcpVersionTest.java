package org.adcontextprotocol.adcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AdcpVersion}.
 */
class AdcpVersionTest {

    @Test
    void v3_constants() {
        assertEquals(3, AdcpVersion.V3.majorVersion());
        assertNull(AdcpVersion.V3.minorVersion());
    }

    @Test
    void v3_1_constants() {
        assertEquals(3, AdcpVersion.V3_1.majorVersion());
        assertEquals("3.1", AdcpVersion.V3_1.minorVersion());
    }

    @Test
    void rejects_zero_major_version() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdcpVersion(0, null));
    }

    @Test
    void rejects_negative_major_version() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdcpVersion(-1, null));
    }

    @Test
    void custom_version() {
        var v = new AdcpVersion(4, "4.2");
        assertEquals(4, v.majorVersion());
        assertEquals("4.2", v.minorVersion());
    }

    @Test
    void rejects_mismatched_minor_version() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdcpVersion(3, "4.1"),
                "minorVersion must start with majorVersion");
    }

    @Test
    void allows_null_minor_version() {
        var v = new AdcpVersion(5, null);
        assertEquals(5, v.majorVersion());
        assertNull(v.minorVersion());
    }
}
