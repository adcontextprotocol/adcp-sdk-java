package org.adcontextprotocol.adcp.transport;

import org.adcontextprotocol.adcp.AdcpVersion;
import org.jspecify.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>SDK-set version fields take precedence over caller args. If a caller
 * attempts to override {@code adcp_major_version}, a warning is logged and
 * the SDK value is used.
 */
public final class VersionEnvelope {

    private static final Logger log = LoggerFactory.getLogger(VersionEnvelope.class);

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
     * SDK version fields take precedence — caller overrides are logged
     * as warnings and discarded.
     *
     * @param callerArgs the caller's arguments (may be empty or null)
     * @param version    the AdCP version
     * @return merged arguments with version envelope
     */
    public static Map<String, Object> mergeInto(
            @Nullable Map<String, Object> callerArgs,
            @Nullable AdcpVersion version) {
        Map<String, Object> envelope = build(version);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (callerArgs != null) {
            for (var entry : callerArgs.entrySet()) {
                if (envelope.containsKey(entry.getKey())) {
                    log.warn("Caller attempted to override SDK version field '{}' "
                            + "(caller={}, SDK={}); SDK value wins",
                            entry.getKey(), entry.getValue(),
                            envelope.get(entry.getKey()));
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        merged.putAll(envelope); // SDK wins
        return merged;
    }
}
