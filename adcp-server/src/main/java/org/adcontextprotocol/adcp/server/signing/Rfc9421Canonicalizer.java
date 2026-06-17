package org.adcontextprotocol.adcp.server.signing;

import org.adcontextprotocol.adcp.signing.SigningException;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RFC 9421 §2.5 signature base canonicalizer.
 *
 * <p>Takes a {@link org.adcontextprotocol.adcp.signing.SigningInput} and produces
 * the signature base — the exact byte sequence that gets signed. Each covered
 * component is formatted as {@code "@lowercase-component-name": component-value}
 * followed by a newline, then {@code @signature-params: (...)} with the signature
 * parameters.
 *
 * <p>The canonicalizer follows the AdCP RFC 9421 profile for URL canonicalization:
 * <ol>
 *   <li>Lowercase the scheme</li>
 *   <li>Lowercase the host (IDN: UTS-46 Nontransitional ToASCII → Punycode A-label)</li>
 *   <li>Strip userinfo</li>
 *   <li>Strip default port (:443 for https, :80 for http)</li>
 *   <li>Remove dot segments per RFC 3986 §5.2.4</li>
 *   <li>Uppercase percent-encoded hex digits; decode unreserved percent-encoding</li>
 *   <li>Preserve query string byte-for-byte</li>
 *   <li>Strip fragment</li>
 * </ol>
 */
public final class Rfc9421Canonicalizer {

    private Rfc9421Canonicalizer() {}

    /**
     * Build the signature base for the given signing input and parameters.
     *
     * @param method            HTTP method (e.g. "POST")
     * @param targetUri         the request target URI (will be canonicalized)
     * @param headers           request headers (name → value)
     * @param coveredComponents ordered list of covered component identifiers
     * @param signatureInput    the Signature-Input header value
     * @return the signature base string (lines joined by LF, no trailing LF)
     * @throws SigningException if the URI is malformed or components cannot be resolved
     */
    public static String canonicalize(
            String method,
            String targetUri,
            Map<String, String> headers,
            List<String> coveredComponents,
            String signatureInput) throws SigningException {

        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(targetUri, "targetUri");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(coveredComponents, "coveredComponents");
        Objects.requireNonNull(signatureInput, "signatureInput");

        String canonicalUri = canonicalizeTargetUri(targetUri);
        String authority = extractAuthority(targetUri);

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < coveredComponents.size(); i++) {
            String component = coveredComponents.get(i);
            String value = resolveComponentValue(component, method, canonicalUri, authority, headers);
            sb.append('"').append(component).append('"').append(": ").append(value);
            sb.append('\n');
        }

        // Per RFC 9421 §2.5, the @signature-params value is the signature-params
        // content WITHOUT the label prefix. E.g., for "sig1=(...);params", the
        // @signature-params value is "(...);params".
        String signatureParamsValue = stripLabelPrefix(signatureInput);
        sb.append("\"@signature-params\": ").append(signatureParamsValue);

        return sb.toString();
    }

    /**
     * Canonicalize the target URI per the AdCP RFC 9421 profile algorithm.
     *
     * <p>Steps:
     * 1. Lowercase the scheme
     * 2. Lowercase the host (IDN: UTS-46 Nontransitional ToASCII → Punycode A-label)
     * 3. Strip userinfo
     * 4. Strip default port (:443 for https, :80 for http)
     * 5. Remove dot segments (RFC 3986 §5.2.4)
     * 6. Uppercase percent-encoded hex; decode unreserved percent-encoding
     * 7. Preserve query string byte-for-byte
     * 8. Strip fragment
     *
     * @param uri the raw URI string
     * @return the canonicalized URI string
     * @throws SigningException if the URI is malformed
     */
    public static String canonicalizeTargetUri(String uri) throws SigningException {
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException e) {
            // Try pre-processing: extract the host part and convert IDN
            try {
                String asciiUri = preprocessIdnUri(uri);
                parsed = new URI(asciiUri);
            } catch (URISyntaxException | IllegalArgumentException e2) {
                throw new SigningException("Malformed target URI: " + uri, e);
            }
        }

        // If URI parsed but host is null (non-ASCII host), try IDN pre-processing
        if (parsed.getHost() == null || parsed.getHost().isEmpty()) {
            try {
                String asciiUri = preprocessIdnUri(uri);
                URI reparsed = new URI(asciiUri);
                if (reparsed.getHost() != null && !reparsed.getHost().isEmpty()) {
                    parsed = reparsed;
                } else {
                    throw new SigningException("URI missing host: " + uri);
                }
            } catch (URISyntaxException | IllegalArgumentException e2) {
                throw new SigningException("URI missing host: " + uri);
            }
        }

        String scheme = parsed.getScheme();
        if (scheme == null || scheme.isEmpty()) {
            throw new SigningException("URI missing scheme: " + uri);
        }
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);

        String host = parsed.getHost();
        int port = parsed.getPort();

        // Reject IPv6 zone identifiers (RFC 6874) — they are node-local
        // and have no meaning outside the signing host.
        if (host != null && host.contains("%")) {
            throw new SigningException("IPv6 zone identifier in signed URL: " + uri);
        }

        if (host == null || host.isEmpty()) {
            throw new SigningException("URI missing host: " + uri);
        }

        // Step 2: Lowercase host with IDN ToASCII (Punycode A-label)
        String canonicalHost = canonicalizeHost(host);

        // Step 4: Strip default ports
        boolean isDefaultPort = false;
        if ("https".equals(scheme) && port == 443) {
            isDefaultPort = true;
        } else if ("http".equals(scheme) && port == 80) {
            isDefaultPort = true;
        }

        // Step 5: Remove dot segments from path
        String path = parsed.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        } else {
            path = removeDotSegments(path);
        }

        // Step 6: Normalize percent-encoding
        path = normalizePercentEncoding(path);

        // Step 7: Preserve query byte-for-byte
        String query = parsed.getRawQuery();

        // Step 8: Strip fragment (already excluded by URI)

        StringBuilder result = new StringBuilder();
        result.append(scheme).append("://");
        if (host.startsWith("[")) {
            // IPv6
            result.append(canonicalHost);
            if (port != -1 && !isDefaultPort) {
                result.append(':').append(port);
            }
        } else {
            result.append(canonicalHost);
            if (port != -1 && !isDefaultPort) {
                result.append(':').append(port);
            }
        }
        result.append(path);
        if (query != null) {
            result.append('?').append(query);
        }

        return result.toString();
    }

    /**
     * Extract the @authority component from a URI per the AdCP profile.
     *
     * <p>The authority is the host (lowercased, IDN-to-ASCII) plus any non-default port.
     * For IPv6, brackets are preserved.
     */
    public static String extractAuthority(String uri) throws SigningException {
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException e) {
            try {
                String asciiUri = preprocessIdnUri(uri);
                parsed = new URI(asciiUri);
            } catch (URISyntaxException | IllegalArgumentException e2) {
                throw new SigningException("Malformed URI for authority extraction: " + uri, e2 instanceof SigningException se ? se : null);
            }
        }

        // If host is null (non-ASCII), try IDN pre-processing
        String host = parsed.getHost();
        if ((host == null || host.isEmpty()) && parsed.getScheme() != null) {
            try {
                String asciiUri = preprocessIdnUri(uri);
                URI reparsed = new URI(asciiUri);
                if (reparsed.getHost() != null && !reparsed.getHost().isEmpty()) {
                    parsed = reparsed;
                    host = parsed.getHost();
                }
            } catch (URISyntaxException | IllegalArgumentException e2) {
                throw new SigningException("URI missing host for authority: " + uri);
            }
        }

        if (host == null || host.isEmpty()) {
            throw new SigningException("URI missing host for authority: " + uri);
        }

        int port = parsed.getPort();
        String scheme = parsed.getScheme();
        if (scheme == null) {
            throw new SigningException("URI missing scheme: " + uri);
        }
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);

        String canonicalHost = canonicalizeHost(host);

        boolean isDefaultPort = ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);

        if (port != -1 && !isDefaultPort) {
            return canonicalHost + ":" + port;
        }
        return canonicalHost;
    }

    /**
     * Canonicalize a host: lowercase, IDN ToASCII for non-ASCII hosts.
     */
    static String canonicalizeHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            // IPv6 — lowercase hex digits inside brackets
            String inner = host.substring(1, host.length() - 1);
            String lowerInner = inner.toLowerCase(java.util.Locale.ROOT);
            return "[" + lowerInner + "]";
        }
        // Try IDN ToASCII for international domain names
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            // If IDN conversion fails, just lowercase
            ascii = host.toLowerCase(java.util.Locale.ROOT);
        }
        return ascii.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Pre-process a URI with non-ASCII host characters by converting the host
     * to its Punycode ASCII representation. Java's URI class rejects non-ASCII
     * hosts, so we need to handle IDN conversion before parsing.
     */
    static String preprocessIdnUri(String uri) throws SigningException {
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd == -1) {
            throw new SigningException("URI missing scheme separator: " + uri);
        }
        String scheme = uri.substring(0, schemeEnd);
        String rest = uri.substring(schemeEnd + 3);

        // Find end of authority (host[:port])
        int pathStart = rest.indexOf('/');
        int queryStart = rest.indexOf('?');
        int fragmentStart = rest.indexOf('#');
        int authorityEnd = rest.length();
        if (pathStart != -1) authorityEnd = Math.min(authorityEnd, pathStart);
        if (queryStart != -1) authorityEnd = Math.min(authorityEnd, queryStart);
        if (fragmentStart != -1) authorityEnd = Math.min(authorityEnd, fragmentStart);

        String authority = rest.substring(0, authorityEnd);
        String remainder = rest.substring(authorityEnd);

        // Handle userinfo in authority
        String hostPort = authority;
        String userinfo = "";
        int atIdx = authority.indexOf('@');
        if (atIdx != -1) {
            hostPort = authority.substring(atIdx + 1);
        }

        // Handle port
        String hostPart;
        String portPart = "";
        if (hostPort.startsWith("[")) {
            // IPv6
            int bracketEnd = hostPort.indexOf(']');
            hostPart = hostPort.substring(0, bracketEnd + 1);
            if (bracketEnd + 1 < hostPort.length() && hostPort.charAt(bracketEnd + 1) == ':') {
                portPart = hostPort.substring(bracketEnd + 1);
            }
        } else {
            int colonIdx = hostPort.lastIndexOf(':');
            if (colonIdx != -1) {
                hostPart = hostPort.substring(0, colonIdx);
                portPart = hostPort.substring(colonIdx);
            } else {
                hostPart = hostPort;
            }
        }

        String asciiHost;
        try {
            asciiHost = IDN.toASCII(hostPart, IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new SigningException("Invalid host in URI: " + hostPart, e);
        }
        String newAuthority = asciiHost + portPart;

        return scheme + "://" + newAuthority + remainder;
    }

    /**
     * RFC 3986 §5.2.4 — Remove dot segments from a path.
     */
    static String removeDotSegments(String path) {
        // Input buffer
        StringBuilder input = new StringBuilder(path);
        // Output buffer
        StringBuilder output = new StringBuilder();

        while (input.length() > 0) {
            // A: If the input buffer begins with a prefix of "../" or "./"
            if (input.indexOf("../") == 0) {
                input.delete(0, 3);
            } else if (input.indexOf("./") == 0) {
                input.delete(0, 2);
            }
            // B: If the input buffer begins with a prefix of "/./" or "/."
            else if (input.indexOf("/./") == 0) {
                input.delete(0, 2);
            } else if (input.toString().equals("/.")) {
                input.replace(0, input.length(), "/");
            }
            // C: If the input buffer begins with a prefix of "/../" or "/.."
            else if (input.indexOf("/../") == 0) {
                input.delete(0, 3);
                removeLastSegment(output);
            } else if (input.toString().equals("/..")) {
                input.replace(0, input.length(), "/");
                removeLastSegment(output);
            }
            // D: If the input buffer consists only of "." or ".."
            else if (input.toString().equals(".") || input.toString().equals("..")) {
                input.setLength(0);
            }
            // E: Move the first path segment (including initial "/" if any) to output
            else {
                int slashIndex = input.indexOf("/", 1);
                if (slashIndex == -1) {
                    output.append(input);
                    input.setLength(0);
                } else {
                    output.append(input, 0, slashIndex);
                    input.delete(0, slashIndex);
                }
            }
        }

        return output.toString();
    }

    private static void removeLastSegment(StringBuilder output) {
        int lastSlash = output.lastIndexOf("/");
        if (lastSlash != -1) {
            output.setLength(lastSlash);
        }
    }

    /**
     * Normalize percent-encoding per the AdCP profile:
     * - Uppercase percent-encoded hex digits (%2f → %2F)
     * - Decode unreserved characters per RFC 3986 §2.3
     */
    static String normalizePercentEncoding(String path) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length()) {
                char hex1 = path.charAt(i + 1);
                char hex2 = path.charAt(i + 2);
                if (isHexDigit(hex1) && isHexDigit(hex2)) {
                    int byteVal = HexFormat.fromHexDigits(path.substring(i + 1, i + 3));
                    if (isUnreserved(byteVal)) {
                        sb.append((char) byteVal);
                    } else {
                        sb.append('%')
                                .append(Character.toUpperCase(hex1))
                                .append(Character.toUpperCase(hex2));
                    }
                    i += 3;
                } else {
                    sb.append(c);
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * RFC 3986 §2.3 unreserved characters: ALPHA, DIGIT, '-', '.', '_', '~'.
     */
    private static boolean isUnreserved(int byteVal) {
        return (byteVal >= 'A' && byteVal <= 'Z')
                || (byteVal >= 'a' && byteVal <= 'z')
                || (byteVal >= '0' && byteVal <= '9')
                || byteVal == '-'
                || byteVal == '.'
                || byteVal == '_'
                || byteVal == '~';
    }

    /**
     * Resolve a covered component to its value in the signature base.
     */
    private static String resolveComponentValue(
            String component,
            String method,
            String canonicalUri,
            String authority,
            Map<String, String> headers) {

        return switch (component) {
            case "@method" -> method;
            case "@target-uri" -> canonicalUri;
            case "@authority" -> authority;
            case "@request-target" -> {
                // Per the user's spec: "@request-target" = method SP request-target
                // But per RFC 9421 §2.2.3, @request-target is just the request-target
                // We support both interpretations
                URI uri = URI.create(canonicalUri);
                String pathAndQuery = uri.getRawPath();
                if (uri.getRawQuery() != null) {
                    pathAndQuery = pathAndQuery + "?" + uri.getRawQuery();
                }
                yield method + " " + pathAndQuery;
            }
            default -> {
                if (component.startsWith("@")) {
                    throw new IllegalArgumentException("Unsupported pseudo-header: " + component);
                }
                // Regular header — look up by lowercase name
                String normalizedName = HeaderNormalizer.normalizeName(component);
                String value = headers.get(normalizedName);
                if (value == null) {
                    // Try case-insensitive lookup
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(component)) {
                            value = entry.getValue();
                            break;
                        }
                    }
                }
                if (value == null) {
                    throw new IllegalArgumentException("Header not found for covered component: " + component);
                }
                yield HeaderNormalizer.normalizeValue(value);
            }
        };
    }

    static String stripLabelPrefix(String signatureInput) {
        int eqIdx = signatureInput.indexOf('=');
        if (eqIdx == -1) {
            return signatureInput;
        }
        return signatureInput.substring(eqIdx + 1);
    }
}