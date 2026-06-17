package org.adcontextprotocol.adcp.signing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SigningContextTest {

    @Test
    void builder_setsUse() {
        SigningContext ctx = SigningContext.builder(AdcpUse.REQUEST_SIGNING).build();
        assertEquals(AdcpUse.REQUEST_SIGNING, ctx.use());
        assertNull(ctx.tenant());
        assertNull(ctx.principal());
    }

    @Test
    void builder_setsTenantAndPrincipal() {
        TenantId tenant = TenantId.of("acme");
        PrincipalRef principal = PrincipalRef.of("user-1");
        SigningContext ctx = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING)
                .tenant(tenant)
                .principal(principal)
                .build();
        assertEquals(AdcpUse.WEBHOOK_SIGNING, ctx.use());
        assertEquals(tenant, ctx.tenant());
        assertEquals(principal, ctx.principal());
    }

    @Test
    void builder_rejectsNullUse() {
        assertThrows(NullPointerException.class, () -> SigningContext.builder(null));
    }

    @Test
    void constructor_rejectsNullUse() {
        assertThrows(NullPointerException.class,
                () -> new SigningContext(null, null, null));
    }

    @Test
    void equality() {
        TenantId t = TenantId.of("t1");
        PrincipalRef p = PrincipalRef.of("p1");
        SigningContext a = SigningContext.builder(AdcpUse.REQUEST_SIGNING).tenant(t).principal(p).build();
        SigningContext b = SigningContext.builder(AdcpUse.REQUEST_SIGNING).tenant(t).principal(p).build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void inequality_differentUse() {
        SigningContext a = SigningContext.builder(AdcpUse.REQUEST_SIGNING).build();
        SigningContext b = SigningContext.builder(AdcpUse.WEBHOOK_SIGNING).build();
        assertNotEquals(a, b);
    }

    @Test
    void tenantId_rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> TenantId.of(""));
        assertThrows(IllegalArgumentException.class, () -> TenantId.of("  "));
    }

    @Test
    void tenantId_rejectsNull() {
        assertThrows(NullPointerException.class, () -> TenantId.of(null));
    }

    @Test
    void principalRef_rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> PrincipalRef.of(""));
        assertThrows(IllegalArgumentException.class, () -> PrincipalRef.of("  "));
    }

    @Test
    void principalRef_rejectsNull() {
        assertThrows(NullPointerException.class, () -> PrincipalRef.of(null));
    }

    @Test
    void nullableFieldsAreNullByDefault() {
        SigningContext ctx = SigningContext.builder(AdcpUse.REQUEST_SIGNING).build();
        assertNull(ctx.tenant());
        assertNull(ctx.principal());
    }

    @Test
    void builder_tenantAndPrincipalCanBeSetToNull() {
        SigningContext ctx = SigningContext.builder(AdcpUse.REQUEST_SIGNING)
                .tenant(TenantId.of("t"))
                .principal(PrincipalRef.of("p"))
                .tenant(null)
                .principal(null)
                .build();
        assertNull(ctx.tenant());
        assertNull(ctx.principal());
    }
}