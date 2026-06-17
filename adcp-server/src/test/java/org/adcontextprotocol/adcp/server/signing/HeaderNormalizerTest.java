package org.adcontextprotocol.adcp.server.signing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeaderNormalizerTest {

    @Test
    void normalizeName_lowercases() {
        assertEquals("content-type", HeaderNormalizer.normalizeName("Content-Type"));
        assertEquals("content-type", HeaderNormalizer.normalizeName("CONTENT-TYPE"));
        assertEquals("content-type", HeaderNormalizer.normalizeName("content-type"));
    }

    @Test
    void normalizeValue_trimsLeadingTrailingOWS() {
        assertEquals("application/json", HeaderNormalizer.normalizeValue("  application/json  "));
        assertEquals("application/json", HeaderNormalizer.normalizeValue("\tapplication/json\t"));
    }

    @Test
    void normalizeValue_collapsesInnerOWS() {
        assertEquals("application/json", HeaderNormalizer.normalizeValue("application/json"));
        assertEquals("a b", HeaderNormalizer.normalizeValue("a  b"));
        assertEquals("a b", HeaderNormalizer.normalizeValue("a\tb"));
        assertEquals("a b", HeaderNormalizer.normalizeValue("a \t b"));
    }

    @Test
    void normalizeValue_mixedOWS() {
        assertEquals("application/json; charset=utf-8",
                HeaderNormalizer.normalizeValue("  application/json; \t charset=utf-8  "));
    }

    @Test
    void normalizeValue_noChangesNeeded() {
        assertEquals("application/json", HeaderNormalizer.normalizeValue("application/json"));
    }

    @Test
    void normalizeValue_emptyString() {
        assertEquals("", HeaderNormalizer.normalizeValue(""));
    }

    @Test
    void normalizeValue_onlyWhitespace() {
        assertEquals("", HeaderNormalizer.normalizeValue("  \t  "));
    }
}