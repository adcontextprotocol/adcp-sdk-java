package org.adcontextprotocol.adcp.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * DNS-pinning resolver that resolves a hostname once, validates every
 * address against an {@link SsrfPolicy}, and returns only the first
 * validated address — preventing DNS-rebinding attacks.
 *
 * <p>Resolution uses {@link InetAddress#getAllByName(String)} (the
 * system resolver). After validation, {@link #rewriteUri(URI, InetAddress, String)}
 * rewrites the target URI to use the pinned IP literal, injecting a
 * {@code Host} header so TLS SNI and virtual-host routing still work.
 */
public final class DnsPinResolver {

    private DnsPinResolver() {}

    /**
     * Resolves {@code host} and validates every returned address against
     * the given {@link SsrfPolicy}. Returns the first validated address.
     *
     * @throws SsrfBlockedException if any resolved address is denied
     * @throws UnknownHostException if the host cannot be resolved
     */
    public static InetAddress resolveAndPin(String host, SsrfPolicy policy) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new UnknownHostException("No addresses resolved for: " + host);
        }

        for (InetAddress addr : addresses) {
            SsrfDecision decision = policy.evaluate(addr);
            if (decision instanceof SsrfDecision.Deny deny) {
                throw new SsrfBlockedException(host, deny.reason());
            }
        }

        // All addresses passed — pin to the first one.
        return addresses[0];
    }

    /**
     * Validates a literal IP address (no DNS resolution needed) against
     * the policy.
     *
     * @throws SsrfBlockedException if the address is denied
     */
    public static void validateAddress(InetAddress address, SsrfPolicy policy) {
        SsrfDecision decision = policy.evaluate(address);
        if (decision instanceof SsrfDecision.Deny deny) {
            throw new SsrfBlockedException(
                    address.getHostAddress(), deny.reason());
        }
    }

    /**
     * Rewrites a URI to use the pinned IP address while preserving the
     * original scheme, port, path, query and fragment. The caller should
     * inject the original hostname via the {@code Host} HTTP header so that
     * TLS SNI and virtual-host routing continue to work.
     *
     * <p>For IPv4 addresses, the host is replaced with the dotted-quad
     * (e.g. {@code 93.184.216.34}). For IPv6, it is wrapped in brackets
     * (e.g. {@code [2606:2800:220:1:248:1893:25c8:1946]}).
     *
     * @param original     the original URI with hostname
     * @param pinned       the validated IP address to connect to
     * @param originalHost the original hostname (used only for logging/diagnostics)
     * @return a new URI targeting the pinned IP
     * @throws IOException if the URI cannot be reconstructed
     */
    public static URI rewriteUri(URI original, InetAddress pinned,
                                 String originalHost) throws IOException {
        String ip = pinned.getHostAddress();
        // IPv6 addresses need brackets in URIs
        if (pinned instanceof java.net.Inet6Address) {
            ip = "[" + ip + "]";
        }
        try {
            // Reconstruct the URI with the IP in place of the hostname
            StringBuilder sb = new StringBuilder();
            sb.append(original.getScheme()).append("://").append(ip);
            if (original.getPort() != -1) {
                sb.append(':').append(original.getPort());
            }
            if (original.getRawPath() != null) {
                sb.append(original.getRawPath());
            }
            if (original.getRawQuery() != null) {
                sb.append('?').append(original.getRawQuery());
            }
            if (original.getRawFragment() != null) {
                sb.append('#').append(original.getRawFragment());
            }
            return URI.create(sb.toString());
        } catch (Exception e) {
            throw new IOException("Failed to rewrite URI for DNS pinning: "
                    + original + " → " + ip, e);
        }
    }
}
