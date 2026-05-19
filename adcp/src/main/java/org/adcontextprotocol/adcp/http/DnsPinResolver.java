package org.adcontextprotocol.adcp.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * DNS validator that resolves a hostname once, validates every returned
 * address against an {@link SsrfPolicy}, and returns the first validated
 * address.
 *
 * <p>Resolution uses {@link InetAddress#getAllByName(String)} (the system
 * resolver). Callers keep the original URI authority unchanged so TLS SNI
 * and hostname verification continue to use the hostname instead of an IP
 * literal. This means validation happens at resolve time only and callers
 * must accept the remaining TOCTOU window between DNS validation and the
 * eventual connect.
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
}
