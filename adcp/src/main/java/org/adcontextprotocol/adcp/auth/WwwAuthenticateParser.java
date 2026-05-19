package org.adcontextprotocol.adcp.auth;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code WWW-Authenticate} headers per RFC 9110 §11.6.1.
 *
 * <p>Handles the common cases seen in AdCP agents:
 * <ul>
 *   <li>{@code Bearer realm="example", error="invalid_token"}</li>
 *   <li>{@code Basic realm="Agent"}</li>
 *   <li>{@code Bearer} (no parameters)</li>
 * </ul>
 *
 * <p>Only parses the first challenge in multi-challenge headers.
 * Quoted-pair escapes ({@code \"}) inside quoted strings are handled
 * per RFC 9110 §5.6.4.
 */
public final class WwwAuthenticateParser {

    // Matches: scheme followed by optional key=value pairs
    private static final Pattern SCHEME_PATTERN =
            Pattern.compile("^(\\S+)\\s*(.*)$");

    // Matches: key="value" (with quoted-pair support) or key=token
    // Group 1: key
    // Group 2: quoted string content (may contain escaped chars)
    // Group 3: unquoted token value
    private static final Pattern PARAM_PATTERN =
            Pattern.compile("(\\w+)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s,]+))");

    /** Maximum number of parameters to parse (DoS guard). */
    private static final int MAX_PARAMS = 16;

    private WwwAuthenticateParser() {}

    /**
     * Parses a {@code WWW-Authenticate} header value into an
     * {@link AuthChallengeInfo}.
     *
     * @param header the header value (e.g. {@code "Bearer realm=\"example\""})
     * @return parsed challenge info, or {@code null} if the header is blank
     */
    public static @Nullable AuthChallengeInfo parse(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }

        Matcher schemeMatcher = SCHEME_PATTERN.matcher(header.trim());
        if (!schemeMatcher.matches()) {
            return null;
        }

        String scheme = schemeMatcher.group(1).toLowerCase(java.util.Locale.ROOT);
        String paramString = schemeMatcher.group(2);

        Map<String, String> params = parseParams(paramString);

        return new AuthChallengeInfo(
                scheme,
                params.get("realm"),
                params.get("scope"),
                params.get("error"),
                params.get("error_description"));
    }

    private static Map<String, String> parseParams(String paramString) {
        Map<String, String> params = new LinkedHashMap<>();
        if (paramString == null || paramString.isBlank()) {
            return params;
        }

        Matcher paramMatcher = PARAM_PATTERN.matcher(paramString);
        int count = 0;
        while (paramMatcher.find() && count < MAX_PARAMS) {
            String key = paramMatcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String value;
            if (paramMatcher.group(2) != null) {
                // Quoted string — unescape quoted-pairs (RFC 9110 §5.6.4)
                value = paramMatcher.group(2).replace("\\\"", "\"")
                                             .replace("\\\\", "\\");
            } else {
                value = paramMatcher.group(3);
            }
            params.put(key, value);
            count++;
        }

        return params;
    }
}
