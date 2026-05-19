package org.adcontextprotocol.adcp;

import org.jspecify.annotations.Nullable;

/**
 * AdCP protocol version identifier.
 *
 * <p>Used by the version envelope injected into every tool call.
 * The {@link #majorVersion()} is always present; the {@link #minorVersion()}
 * is set only when a specific minor version is required.
 *
 * @param majorVersion the major protocol version (e.g. 3)
 * @param minorVersion optional minor version string (e.g. "3.1"), or {@code null}
 */
public record AdcpVersion(int majorVersion, @Nullable String minorVersion) {

    private static final java.util.regex.Pattern MINOR_VERSION_PATTERN =
            java.util.regex.Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");

    /** AdCP v3.0 (current default). */
    public static final AdcpVersion V3 = new AdcpVersion(3, null);

    /** AdCP v3.1. */
    public static final AdcpVersion V3_1 = new AdcpVersion(3, "3.1");

    public AdcpVersion {
        if (majorVersion < 1) {
            throw new IllegalArgumentException("majorVersion must be >= 1: " + majorVersion);
        }
        if (minorVersion != null) {
            if (minorVersion.length() > 20) {
                throw new IllegalArgumentException(
                        "minorVersion too long: " + minorVersion.length());
            }
            if (!MINOR_VERSION_PATTERN.matcher(minorVersion).matches()) {
                throw new IllegalArgumentException(
                        "minorVersion must be a version string (e.g. '3.1'): "
                                + minorVersion);
            }
            if (!minorVersion.startsWith(majorVersion + ".")) {
                throw new IllegalArgumentException(
                        "minorVersion must start with majorVersion: " + minorVersion);
            }
        }
    }
}
