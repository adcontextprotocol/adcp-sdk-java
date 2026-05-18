package org.adcontextprotocol.adcp.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DnsPinResolver}.
 */
class DnsPinResolverTest {

    @Test
    void resolveAndPin_blocks_loopback() {
        assertThrows(SsrfBlockedException.class,
                () -> DnsPinResolver.resolveAndPin("127.0.0.1", SsrfPolicy.strict()));
    }

    @Test
    void resolveAndPin_blocks_rfc1918() {
        assertThrows(SsrfBlockedException.class,
                () -> DnsPinResolver.resolveAndPin("10.0.0.1", SsrfPolicy.strict()));
    }

    @Test
    void resolveAndPin_blocks_link_local() {
        assertThrows(SsrfBlockedException.class,
                () -> DnsPinResolver.resolveAndPin("169.254.169.254", SsrfPolicy.strict()));
    }

    @Test
    void resolveAndPin_allows_with_permissive_policy() throws IOException {
        InetAddress addr = DnsPinResolver.resolveAndPin(
                "127.0.0.1", SsrfPolicy.permissive());
        assertNotNull(addr);
    }

    @Test
    void validateAddress_blocks_denied() {
        InetAddress addr;
        try {
            addr = InetAddress.getByName("10.0.0.1");
        } catch (UnknownHostException e) {
            fail("Could not resolve 10.0.0.1", e);
            return;
        }
        assertThrows(SsrfBlockedException.class,
                () -> DnsPinResolver.validateAddress(addr, SsrfPolicy.strict()));
    }

    @Test
    void validateAddress_allows_public() throws UnknownHostException {
        InetAddress addr = InetAddress.getByName("8.8.8.8");
        assertDoesNotThrow(
                () -> DnsPinResolver.validateAddress(addr, SsrfPolicy.strict()));
    }

    @Test
    void ssrfBlockedException_carries_reason() {
        try {
            DnsPinResolver.resolveAndPin("127.0.0.1", SsrfPolicy.strict());
            fail("Expected SsrfBlockedException");
        } catch (SsrfBlockedException e) {
            assertEquals("127.0.0.1", e.host());
            assertFalse(e.reason().isBlank());
        } catch (IOException e) {
            fail("Unexpected IOException", e);
        }
    }
}
