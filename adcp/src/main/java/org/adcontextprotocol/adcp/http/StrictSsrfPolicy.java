package org.adcontextprotocol.adcp.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Strict SSRF policy — the v0.1 baseline. Denies the address ranges
 * listed in {@code specs/ssrf-baseline.md} §"Address guards (block list)".
 *
 * <p>Implementation philosophy: walk through {@code InetAddress}'s built-in
 * range methods rather than re-rolling CIDR matchers, since the JDK already
 * encodes the same RFCs. Anything {@link InetAddress} flags as link-local,
 * site-local, loopback, multicast, or "any local" is denied. The
 * IPv4-mapped-IPv6 case is handled by extracting the embedded v4 address.
 */
final class StrictSsrfPolicy implements SsrfPolicy {

    static final StrictSsrfPolicy INSTANCE = new StrictSsrfPolicy();

    private StrictSsrfPolicy() {}

    @Override
    public SsrfDecision evaluate(InetAddress address) {
        InetAddress effective = unmapIpv4Mapped(address);

        if (effective.isAnyLocalAddress()) {
            return new SsrfDecision.Deny("any-local address (0.0.0.0 / ::)");
        }
        if (effective.isLoopbackAddress()) {
            return new SsrfDecision.Deny("loopback (127.0.0.0/8 or ::1)");
        }
        if (effective.isLinkLocalAddress()) {
            return new SsrfDecision.Deny("link-local (includes cloud-metadata endpoints)");
        }
        if (effective.isSiteLocalAddress()) {
            return new SsrfDecision.Deny("RFC 1918 private (10/8, 172.16/12, 192.168/16)");
        }
        if (effective.isMulticastAddress()) {
            return new SsrfDecision.Deny("multicast (224.0.0.0/4 or ff00::/8)");
        }
        if (effective instanceof Inet4Address v4) {
            if (isCarrierGradeNat(v4)) {
                return new SsrfDecision.Deny("RFC 6598 carrier-grade NAT (100.64/10)");
            }
            if (isBenchmark(v4)) {
                return new SsrfDecision.Deny("RFC 2544 benchmark (198.18/15)");
            }
            if (isIetfProtocolAssignments(v4)) {
                return new SsrfDecision.Deny("RFC 6890 IETF protocol assignments (192.0.0/24)");
            }
            if (isReservedClassE(v4)) {
                return new SsrfDecision.Deny("reserved (240.0.0.0/4)");
            }
        }
        if (effective instanceof Inet6Address v6 && isIpv6UniqueLocal(v6)) {
            return new SsrfDecision.Deny("IPv6 unique local (fc00::/7)");
        }
        return SsrfDecision.ALLOW;
    }

    private static InetAddress unmapIpv4Mapped(InetAddress address) {
        // ::ffff:0:0/96 — an IPv4 address tunneled inside an IPv6 address.
        // The JDK's range methods evaluate the v6 form, not the embedded v4,
        // so we unwrap to apply the v4 ranges (RFC 1918 etc.) to the
        // effective destination.
        //
        // Note: Inet6Address.isIPv4CompatibleAddress() checks the legacy
        // "::a.b.c.d" form (which also matches ::1), not the IPv4-mapped
        // "::ffff:a.b.c.d" form we want. We test the bytes directly.
        if (!(address instanceof Inet6Address v6)) {
            return address;
        }
        byte[] addr = v6.getAddress();
        // First 80 bits zero, next 16 bits 0xFFFF — the IPv4-mapped form.
        for (int i = 0; i < 10; i++) {
            if (addr[i] != 0) {
                return address;
            }
        }
        if ((addr[10] & 0xFF) != 0xFF || (addr[11] & 0xFF) != 0xFF) {
            return address;
        }
        byte[] v4Bytes = new byte[]{addr[12], addr[13], addr[14], addr[15]};
        try {
            return InetAddress.getByAddress(v4Bytes);
        } catch (Exception ignored) {
            return address;
        }
    }

    private static boolean isCarrierGradeNat(Inet4Address v4) {
        byte[] b = v4.getAddress();
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }

    private static boolean isBenchmark(Inet4Address v4) {
        byte[] b = v4.getAddress();
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 198 && (second == 18 || second == 19);
    }

    private static boolean isIetfProtocolAssignments(Inet4Address v4) {
        byte[] b = v4.getAddress();
        return (b[0] & 0xFF) == 192 && (b[1] & 0xFF) == 0 && (b[2] & 0xFF) == 0;
    }

    private static boolean isReservedClassE(Inet4Address v4) {
        int first = v4.getAddress()[0] & 0xFF;
        return first >= 240;
    }

    private static boolean isIpv6UniqueLocal(Inet6Address v6) {
        int firstByte = v6.getAddress()[0] & 0xFF;
        // fc00::/7 — the first byte is 0xFC or 0xFD.
        return firstByte == 0xFC || firstByte == 0xFD;
    }
}
