package org.adcontextprotocol.adcp.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * DNS-pinning resolver that resolves a hostname once, validates every
 * address against an {@link SsrfPolicy}, and returns only the first
 * validated address — preventing DNS-rebinding attacks.
 *
 * <p>This uses the JDK 21 {@link InetAddressResolverProvider} SPI.
 * The resolver is not installed globally; instead, {@link AdcpHttpClient}
 * resolves via this class before each request and pins the connection
 * to the validated IP.
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
}
