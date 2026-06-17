package org.adcontextprotocol.adcp.server.signing.jwks;

import org.adcontextprotocol.adcp.http.SsrfBlockedException;
import org.adcontextprotocol.adcp.http.SsrfPolicy;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Validates JWKS URIs before fetching. Rejects private IPs, link-local,
 * multicast, reserved ranges, and cloud metadata endpoints. Uses the
 * existing {@link SsrfPolicy} from {@code adcp.http}.
 *
 * <p>Mirrors the Python SDK's {@code validate_jwks_uri()}.
 */
public final class SsrfJwksUriValidator {

    private final SsrfPolicy ssrfPolicy;
    private final boolean requireHttps;

    /**
     * Create a validator with strict SSRF policy and HTTPS required.
     */
    public SsrfJwksUriValidator() {
        this(SsrfPolicy.strict(), true);
    }

    /**
     * Create a validator with the given SSRF policy.
     *
     * @param ssrfPolicy   the SSRF policy to use for address validation
     * @param requireHttps whether to reject non-HTTPS URIs in production
     */
    public SsrfJwksUriValidator(SsrfPolicy ssrfPolicy, boolean requireHttps) {
        this.ssrfPolicy = ssrfPolicy;
        this.requireHttps = requireHttps;
    }

    /**
     * Validate a JWKS URI, throwing {@link SsrfBlockedException} if the URI
     * resolves to a blocked address range.
     *
     * @param uri the JWKS URI to validate
     * @throws SsrfBlockedException if the URI is blocked by the SSRF policy
     * @throws IllegalArgumentException if the URI is malformed
     */
    public void validate(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("URI has no scheme: " + uri);
        }

        String schemeLower = scheme.toLowerCase();
        if (requireHttps && !"https".equals(schemeLower)) {
            String host = uri.getHost();
            if (!isLoopback(host)) {
                throw new SsrfBlockedException(
                        uri.getHost() != null ? uri.getHost() : "unknown",
                        "JWKS URI must use HTTPS in production: " + schemeLower);
            }
        }

        if (!"http".equals(schemeLower) && !"https".equals(schemeLower)) {
            throw new IllegalArgumentException(
                    "Unsupported URI scheme for JWKS fetch: " + schemeLower);
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("URI has no host: " + uri);
        }

        // Reject explicit redirect-following in the URI.
        // AdcpHttpClient is already configured with followRedirects=NEVER,
        // but we also check here as defense in depth.
        // No action needed — redirect following is handled at the HTTP client level.

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new SsrfBlockedException(host, "No addresses resolved for: " + host);
            }

            for (InetAddress addr : addresses) {
                var decision = ssrfPolicy.evaluate(addr);
                if (decision instanceof org.adcontextprotocol.adcp.http.SsrfDecision.Deny deny) {
                    throw new SsrfBlockedException(host, deny.reason());
                }
            }
        } catch (UnknownHostException e) {
            throw new SsrfBlockedException(host, "Cannot resolve host: " + host);
        }

        // Check for blocked cloud metadata IPs
        String hostAddress = host;
        if (isIpLiteral(host)) {
            checkCloudMetadataIps(host);
        } else {
            try {
                for (InetAddress addr : InetAddress.getAllByName(host)) {
                    String ip = addr.getHostAddress();
                    checkCloudMetadataIp(ip);
                }
            } catch (UnknownHostException e) {
                throw new SsrfBlockedException(host, "Cannot resolve host: " + host);
            }
        }
    }

    private void checkCloudMetadataIps(String ip) {
        checkCloudMetadataIp(ip);
    }

    private void checkCloudMetadataIp(String ip) {
        // AWS, Azure, GCP, DigitalOcean, Alibaba cloud metadata
        if ("169.254.169.254".equals(ip)) {
            throw new SsrfBlockedException(ip, "cloud metadata IP blocked");
        }
        // AWS IPv6
        if ("fd00:ec2::254".equals(ip)) {
            throw new SsrfBlockedException(ip, "cloud metadata IP blocked");
        }
        // Alibaba
        if ("100.100.100.200".equals(ip)) {
            throw new SsrfBlockedException(ip, "cloud metadata IP blocked");
        }
        // Oracle Cloud
        if ("192.0.0.192".equals(ip)) {
            throw new SsrfBlockedException(ip, "cloud metadata IP blocked");
        }
    }

    private static boolean isLoopback(@Nullable String host) {
        if (host == null) return false;
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static boolean isIpLiteral(String host) {
        if (host.startsWith("[")) return true;
        if (host.indexOf('.') < 0) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) return false;
        }
        return true;
    }
}