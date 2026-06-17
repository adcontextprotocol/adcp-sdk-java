package org.adcontextprotocol.adcp.server.signing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ETldPlusOneTest {

    @Test
    void extract_standardDomain_returnsRegistrableDomain() {
        assertEquals("example.com", ETldPlusOne.extract("www.example.com"));
        assertEquals("example.com", ETldPlusOne.extract("example.com"));
        assertEquals("example.co.uk", ETldPlusOne.extract("www.example.co.uk"));
        assertEquals("example.co.uk", ETldPlusOne.extract("sub.example.co.uk"));
    }

    @Test
    void extract_multiLevelSubdomain_returnsRegistrableDomain() {
        assertEquals("example.com", ETldPlusOne.extract("a.b.c.example.com"));
    }

    @Test
    void extract_ipLiteral_returnsNull() {
        assertNull(ETldPlusOne.extract("192.168.1.1"));
        assertNull(ETldPlusOne.extract("[2001:db8::1]"));
    }

    @Test
    void extract_singleLabel_returnsNull() {
        assertNull(ETldPlusOne.extract("localhost"));
        assertNull(ETldPlusOne.extract("intranet"));
    }

    @Test
    void extract_publicSuffix_itself_returnsNull() {
        assertNull(ETldPlusOne.extract("co.uk"));
        assertNull(ETldPlusOne.extract("com"));
        assertNull(ETldPlusOne.extract("github.io"));
    }

    @Test
    void extract_nullOrEmpty_returnsNull() {
        assertNull(ETldPlusOne.extract(null));
        assertNull(ETldPlusOne.extract(""));
    }

    @Test
    void extract_platformSubdomain_returnsRegistrableDomain() {
        assertEquals("example.vercel.app", ETldPlusOne.extract("example.vercel.app"));
        assertEquals("example.github.io", ETldPlusOne.extract("example.github.io"));
        assertEquals("example.pages.dev", ETldPlusOne.extract("sub.example.pages.dev"));
    }

    @Test
    void extract_caseInsensitive() {
        assertEquals("example.com", ETldPlusOne.extract("WWW.EXAMPLE.COM"));
        assertEquals("example.com", ETldPlusOne.extract("Example.Com"));
    }

    @Test
    void extract_trailingDot_handled() {
        String result = ETldPlusOne.extract("www.example.com.");
        assertEquals("example.com", result);
    }
}