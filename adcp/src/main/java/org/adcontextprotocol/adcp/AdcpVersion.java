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

    /**
     * Parses a release-precision version string (e.g. {@code "3.0"}, {@code "3.1"})
     * into an {@code AdcpVersion}.
     *
     * <p>This is the string-based convenience factory — pass the same value you
     * would set in the Python SDK or TS SDK {@code adcpVersion} constructor option.
     *
     * @param releaseVersion release-precision version (major.minor, e.g. {@code "3.0"})
     * @return parsed {@code AdcpVersion}
     * @throws IllegalArgumentException if the string is not in major.minor format
     */
    public static AdcpVersion of(String releaseVersion) {
        java.util.Objects.requireNonNull(releaseVersion, "releaseVersion");
        if (!MINOR_VERSION_PATTERN.matcher(releaseVersion).matches()) {
            throw new IllegalArgumentException(
                    "releaseVersion must be in major.minor format (e.g. '3.0'): "
                            + releaseVersion);
        }
        int dotIndex = releaseVersion.indexOf('.');
        int major = Integer.parseInt(releaseVersion.substring(0, dotIndex));
        return new AdcpVersion(major, releaseVersion);
    }
}
