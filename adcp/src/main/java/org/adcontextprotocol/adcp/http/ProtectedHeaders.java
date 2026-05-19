package org.adcontextprotocol.adcp.http;

import java.util.Locale;
import java.util.Set;

/**
 * Headers that must never be overridden by caller-supplied values.
 *
 * <p>Shared between {@link AdcpHttpClient} and the MCP transport layer
 * to prevent duplication drift.
 */
public final class ProtectedHeaders {

    /** Headers that SDK-managed transports must not allow callers to set. */
    public static final Set<String> NAMES = Set.of(
            "host", "user-agent", "content-length", "transfer-encoding",
            "connection", "upgrade");

    private ProtectedHeaders() {}

    /** Returns {@code true} if the given header name is protected (case-insensitive). */
    public static boolean isProtected(String name) {
        return NAMES.contains(name.toLowerCase(Locale.ROOT));
    }
}
