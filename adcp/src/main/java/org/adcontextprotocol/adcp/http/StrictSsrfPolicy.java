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
        // Unwrap both IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible
        // (::a.b.c.d) IPv6 addresses so that the embedded IPv4 address
        // gets evaluated against the IPv4 block ranges. The compatible
        // form is deprecated (RFC 4291 §2.5.5.1) but still parsed by
        // JDK's InetAddress, and JDK's range methods (isLoopback, etc.)
        // return false for these addresses — making them an SSRF vector.
        if (!(address instanceof Inet6Address v6)) {
            return address;
        }
        byte[] addr = v6.getAddress();
        // First 80 bits must be zero (common to both forms)
        for (int i = 0; i < 10; i++) {
            if (addr[i] != 0) {
                return address;
            }
        }
        // IPv4-mapped: bytes 10-11 = 0xFF, 0xFF
        boolean isMapped = (addr[10] & 0xFF) == 0xFF && (addr[11] & 0xFF) == 0xFF;
        // IPv4-compatible: bytes 10-11 = 0x00, 0x00 (and not all-zeros/::1)
        boolean isCompat = addr[10] == 0 && addr[11] == 0;
        if (!isMapped && !isCompat) {
            return address;
        }
        // Guard: don't unwrap :: (all zeros) or ::1 — those are already
        // handled by isAnyLocalAddress() / isLoopbackAddress()
        if (isCompat && addr[12] == 0 && addr[13] == 0
                && addr[14] == 0 && (addr[15] == 0 || addr[15] == 1)) {
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
