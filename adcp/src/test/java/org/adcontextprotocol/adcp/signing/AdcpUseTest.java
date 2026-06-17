package org.adcontextprotocol.adcp.signing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdcpUseTest {

    @Test
    void wireName_requestSigning() {
        assertEquals("adcp_req", AdcpUse.REQUEST_SIGNING.wireName());
    }

    @Test
    void wireName_webhookSigning() {
        assertEquals("adcp_whk", AdcpUse.WEBHOOK_SIGNING.wireName());
    }

    @Test
    void fromWireName_roundTrip() {
        for (AdcpUse use : AdcpUse.values()) {
            assertEquals(use, AdcpUse.fromWireName(use.wireName()));
        }
    }

    @Test
    void fromWireName_unknownThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> AdcpUse.fromWireName("unknown"));
    }

    @Test
    void fromWireName_nullThrows() {
        assertThrows(NullPointerException.class,
                () -> AdcpUse.fromWireName(null));
    }

    @Test
    void enumValues() {
        AdcpUse[] values = AdcpUse.values();
        assertEquals(2, values.length);
        assertEquals(AdcpUse.REQUEST_SIGNING, values[0]);
        assertEquals(AdcpUse.WEBHOOK_SIGNING, values[1]);
    }
}