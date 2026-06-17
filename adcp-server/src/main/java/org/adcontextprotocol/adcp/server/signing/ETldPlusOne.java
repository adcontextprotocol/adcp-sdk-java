package org.adcontextprotocol.adcp.server.signing;

import org.jspecify.annotations.Nullable;

import java.net.IDN;
import java.util.Set;

/**
 * Effective TLD+1 (registrable domain) extraction for the key-origin
 * consistency check (AdCP #3690).
 *
 * <p>Computes the eTLD+1 (also called "registrable domain") of a hostname.
 * Two hosts sharing an eTLD+1 are considered same-origin for the purpose of
 * the key-origin binding. Hosts where the eTLD+1 cannot be derived (raw IPs,
 * single-label hosts, hosts that are themselves public suffixes) yield
 * {@code null} — callers must treat {@code null} as a binding failure, not
 * a soft skip.
 *
 * <p>Uses a hardcoded set of public suffixes covering the most common TLDs.
 * Java's {@link IDN} handles IDN (internationalized domain name)
 * canonicalization.
 */
public final class ETldPlusOne {

    private ETldPlusOne() {}

    private static final Set<String> PUBLIC_SUFFIXES = Set.of(
            "com", "net", "org", "edu", "gov", "mil", "io", "co", "co.uk",
            "co.jp", "co.kr", "co.nz", "co.za", "co.in",
            "com.au", "com.br", "com.cn", "com.de", "com.es", "com.fr",
            "com.in", "com.it", "com.mx", "com.nl", "com.sg", "com.tr",
            "com.tw", "com.uk", "com.ua",
            "ac.uk", "org.uk", "me.uk", "gov.uk",
            "org.au", "net.au", "edu.au",
            "ne.jp", "or.jp", "ac.jp",
            "dev", "app", "page", "cloud", "site", "live",
            "info", "biz", "name", "mobi", "pro", "tv", "cc",
            "de", "fr", "it", "es", "nl", "pl", "ru", "se", "no", "fi",
            "dk", "at", "ch", "be", "cz", "pt", "ro", "hu", "gr", "ie",
            "nz", "za", "sg", "hk", "tw", "jp", "kr", "in", "br", "mx",
            "ar", "cl", "pe", "ve", "ua",
            "vercel.app", "netlify.app", "github.io", "gitlab.io",
            "herokuapp.com", "azurewebsites.net", "pages.dev",
            "cloudfront.net", "s3.amazonaws.com", "s3-website.amazonaws.com",
            "blob.core.windows.net", "weebly.com", "wordpress.com",
            "blogspot.com", "shopify.com", "square.site",
            "amazonaws.com", "googleapis.com",
            "onrender.com", "fly.dev", "railway.app",
            "glitch.me", "repl.co", "deno.dev"
    );

    /**
     * Extract the eTLD+1 (registrable domain) from a hostname.
     *
     * <p>Returns {@code null} when:
     * <ul>
     *   <li>The host is an IP literal (v4 or v6)</li>
     *   <li>The host is a single label (e.g. {@code localhost})</li>
     *   <li>The host is itself a public suffix (e.g. {@code co.uk})</li>
     * </ul>
     *
     * @param host the hostname to extract from
     * @return the eTLD+1, or {@code null} if it cannot be derived
     */
    public static @Nullable String extract(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }

        if (isIpLiteral(host)) {
            return null;
        }

        String normalized = IDN.toASCII(host.toLowerCase());
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        String[] labels = normalized.split("\\.");
        if (labels.length < 2) {
            return null;
        }

        int suffixStart = findPublicSuffixStart(labels);
        if (suffixStart < 0) {
            if (labels.length >= 2) {
                return normalized;
            }
            return null;
        }

        if (suffixStart == 0) {
            return null;
        }

        String result = joinLabels(labels, suffixStart - 1, labels.length - 1);

        if (PUBLIC_SUFFIXES.contains(result)) {
            return null;
        }

        return result;
    }

    private static int findPublicSuffixStart(String[] labels) {
        for (int start = 0; start < labels.length; start++) {
            String candidate = joinLabels(labels, start, labels.length - 1);
            if (PUBLIC_SUFFIXES.contains(candidate)) {
                return start;
            }
        }
        return -1;
    }

    private static boolean isIpLiteral(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return true;
        }
        try {
            String[] parts = host.split("\\.");
            if (parts.length == 4) {
                for (String part : parts) {
                    int val = Integer.parseInt(part);
                    if (val < 0 || val > 255) return false;
                }
                return true;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return false;
    }

    private static String joinLabels(String[] labels, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (i > from) sb.append('.');
            sb.append(labels[i]);
        }
        return sb.toString();
    }
}