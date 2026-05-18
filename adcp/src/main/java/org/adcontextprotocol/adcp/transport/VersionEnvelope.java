package org.adcontextprotocol.adcp.transport;

import org.adcontextprotocol.adcp.AdcpVersion;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the version envelope injected into every tool call's arguments.
 *
 * <p>Per the AdCP protocol, every request carries:
 * <ul>
 *   <li>{@code adcp_major_version} — always present (e.g. {@code 3})</li>
 *   <li>{@code adcp_version} — present only when a specific minor version
 *       is pinned (e.g. {@code "3.1"})</li>
 * </ul>
 *
 * <p>Caller-supplied args win if they collide (conformance override).
 */
public final class VersionEnvelope {

    private VersionEnvelope() {}

    /**
     * Builds a version envelope map for the given protocol version.
     *
     * @param version the AdCP version (may be {@code null} for default v3)
     * @return map with version fields
     */
    public static Map<String, Object> build(@Nullable AdcpVersion version) {
        AdcpVersion v = version != null ? version : AdcpVersion.V3;
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("adcp_major_version", v.majorVersion());
        if (v.minorVersion() != null) {
            envelope.put("adcp_version", v.minorVersion());
        }
        return envelope;
    }

    /**
     * Merges the version envelope into the tool call arguments.
     * Caller-supplied args take precedence (conformance override).
     *
     * @param callerArgs the caller's arguments (may be empty, never null)
     * @param version    the AdCP version
     * @return merged arguments with version envelope
     */
    public static Map<String, Object> mergeInto(
            Map<String, Object> callerArgs,
            @Nullable AdcpVersion version) {
        Map<String, Object> merged = new LinkedHashMap<>(build(version));
        merged.putAll(callerArgs); // caller wins
        return merged;
    }
}
