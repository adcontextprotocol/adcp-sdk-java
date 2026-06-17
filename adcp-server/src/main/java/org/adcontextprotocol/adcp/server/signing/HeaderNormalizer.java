package org.adcontextprotocol.adcp.server.signing;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * RFC 9421 header name and value normalization.
 *
 * <p>Per RFC 9421 §2.1 and §4.1:
 * <ul>
 *   <li>Header field names are lowercased.</li>
 *   <li>Leading and trailing OWS (spaces, tabs) are trimmed from values.</li>
 *   <li>Inner OWS (sequences of SP/HTAB) is collapsed to a single SP.</li>
 * </ul>
 */
public final class HeaderNormalizer {

    private static final Pattern INNER_OWS = Pattern.compile("[ \\t]+");

    private HeaderNormalizer() {}

    /**
     * Normalize a header field name: lowercase ASCII.
     *
     * @param name the raw header field name
     * @return the lowercased name
     */
    public static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Normalize a header field value: trim leading/trailing OWS,
     * collapse inner OWS to single SP.
     *
     * @param value the raw header field value
     * @return the normalized value
     */
    public static String normalizeValue(String value) {
        String collapsed = INNER_OWS.matcher(value).replaceAll(" ");
        return collapsed.strip();
    }
}