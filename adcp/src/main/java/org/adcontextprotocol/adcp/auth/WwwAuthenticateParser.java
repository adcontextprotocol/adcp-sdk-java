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
 */
public final class WwwAuthenticateParser {

    // Matches: scheme followed by optional key=value pairs
    // Group 1: scheme (one or more non-space characters)
    // Group 2: the rest (parameters)
    private static final Pattern SCHEME_PATTERN =
            Pattern.compile("^(\\S+)\\s*(.*)$");

    // Matches: key="value" or key=token (unquoted)
    private static final Pattern PARAM_PATTERN =
            Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))");

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
        while (paramMatcher.find()) {
            String key = paramMatcher.group(1).toLowerCase();
            // Prefer quoted value (group 2), fall back to unquoted (group 3)
            String value = paramMatcher.group(2) != null
                    ? paramMatcher.group(2)
                    : paramMatcher.group(3);
            params.put(key, value);
        }

        return params;
    }
}
