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

    @Test
    void rejects_minor_version_with_invalid_characters() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdcpVersion(3, "3.\nFake-Log-Entry"),
                "minorVersion must be a version string");
    }

    @Test
    void rejects_minor_version_too_long() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdcpVersion(3, "3.1234567890123456789"),
                "minorVersion too long");
    }

    @Test
    void accepts_three_part_minor_version() {
        var v = new AdcpVersion(3, "3.1.2");
        assertEquals("3.1.2", v.minorVersion());
    }

    // -- AdcpVersion.of(String) --

    @Test
    void of_parses_release_precision_version() {
        AdcpVersion v = AdcpVersion.of("3.0");
        assertEquals(3, v.majorVersion());
        assertEquals("3.0", v.minorVersion());
    }

    @Test
    void of_parses_minor_version() {
        AdcpVersion v = AdcpVersion.of("3.1");
        assertEquals(3, v.majorVersion());
        assertEquals("3.1", v.minorVersion());
    }

    @Test
    void of_rejects_major_only_string() {
        assertThrows(IllegalArgumentException.class, () -> AdcpVersion.of("3"));
    }

    @Test
    void of_rejects_non_numeric() {
        assertThrows(IllegalArgumentException.class, () -> AdcpVersion.of("abc.def"));
    }

    @Test
    void of_rejects_null() {
        assertThrows(NullPointerException.class, () -> AdcpVersion.of(null));
    }

    // -- AdcpSdkVersion constants (build-time generated) --

    @Test
    void sdk_major_version_is_positive() {
        assertTrue(AdcpSdkVersion.SDK_MAJOR_VERSION > 0,
                "SDK_MAJOR_VERSION must be a positive integer");
    }

    @Test
    void sdk_release_version_matches_major() {
        String release = AdcpSdkVersion.SDK_RELEASE_VERSION;
        assertTrue(release.startsWith(AdcpSdkVersion.SDK_MAJOR_VERSION + "."),
                "SDK_RELEASE_VERSION must start with SDK_MAJOR_VERSION: " + release);
    }
}

