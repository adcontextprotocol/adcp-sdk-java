package org.adcontextprotocol.adcp.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec tests for {@link StrictSsrfPolicy}. The bar these set is the block
 * table in {@code specs/ssrf-baseline.md}; every row gets a parametrized
 * literal address denial case.
 *
 * <p>DNS-rebinding and redirect-follow cases live on the {@code transport}
 * track since they need the live {@code AdcpHttpClient} call path.
 */
class StrictSsrfPolicyTest {

    private final SsrfPolicy policy = SsrfPolicy.strict();

    @ParameterizedTest(name = "denies {0}")
    @ValueSource(strings = {
            "0.0.0.0",                       // any-local
            "127.0.0.1", "127.0.0.53",       // loopback
            "10.0.0.1", "10.255.255.254",    // RFC 1918
            "172.16.0.1", "172.31.255.254",  // RFC 1918
            "192.168.0.1", "192.168.1.1",    // RFC 1918
            "169.254.169.254",               // cloud metadata
            "169.254.0.1",                   // link-local
            "100.64.0.1", "100.127.255.254", // RFC 6598 CGN
            "192.0.0.1", "192.0.0.255",      // RFC 6890 IETF
            "198.18.0.1", "198.19.255.254",  // RFC 2544 benchmark
            "224.0.0.1", "239.255.255.254",  // multicast
            "240.0.0.1", "255.255.255.254",  // reserved class E
            "::1",                           // IPv6 loopback
            "fe80::1",                       // IPv6 link-local
            "fc00::1", "fd00::1",            // IPv6 unique local
            "ff02::1",                       // IPv6 multicast
            "::ffff:127.0.0.1",              // IPv4-mapped IPv6 loopback
            "::ffff:10.0.0.1",               // IPv4-mapped IPv6 RFC 1918
            "::ffff:169.254.169.254"         // IPv4-mapped IPv6 cloud metadata
    })
    void denies_block_table(String literal) throws UnknownHostException {
        InetAddress addr = InetAddress.getByName(literal);
        SsrfDecision decision = policy.evaluate(addr);
        SsrfDecision.Deny deny = assertInstanceOf(SsrfDecision.Deny.class, decision,
                () -> literal + " (" + addr + ") should be denied but was " + decision);
        // Reason text is a short human-readable string — non-empty so
        // operators see something useful in logs.
        assertTrue(!deny.reason().isBlank(), "deny reason should not be blank");
    }

    @ParameterizedTest(name = "allows {0}")
    @ValueSource(strings = {
            "8.8.8.8",                       // Google public DNS
            "1.1.1.1",                       // Cloudflare public DNS
            "93.184.216.34",                 // example.com (stable for the test)
            "2606:4700:4700::1111",          // Cloudflare public IPv6
    })
    void allows_public(String literal) throws UnknownHostException {
        InetAddress addr = InetAddress.getByName(literal);
        SsrfDecision decision = policy.evaluate(addr);
        assertInstanceOf(SsrfDecision.Allow.class, decision,
                () -> literal + " should be allowed but was " + decision);
    }

    @Test
    @DisplayName("Deny.reason() never contains the rejected address itself (no host-structure leak)")
    void deny_reason_does_not_leak_address() throws UnknownHostException {
        InetAddress addr = InetAddress.getByName("169.254.169.254");
        SsrfDecision.Deny deny = (SsrfDecision.Deny) policy.evaluate(addr);
        assertTrue(!deny.reason().contains("169.254.169.254"),
                "deny reason should describe the range, not echo the address: " + deny.reason());
    }

    @Test
    void permissive_allows_everything() throws UnknownHostException {
        SsrfPolicy permissive = SsrfPolicy.permissive();
        assertEquals(new SsrfDecision.Allow(),
                permissive.evaluate(InetAddress.getByName("127.0.0.1")));
        assertEquals(new SsrfDecision.Allow(),
                permissive.evaluate(InetAddress.getByName("169.254.169.254")));
        assertEquals(new SsrfDecision.Allow(),
                permissive.evaluate(InetAddress.getByName("10.0.0.1")));
    }
}
