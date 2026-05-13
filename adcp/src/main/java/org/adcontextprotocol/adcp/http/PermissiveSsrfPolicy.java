package org.adcontextprotocol.adcp.http;

import java.net.InetAddress;

/**
 * Permissive SSRF policy — local development only. Allows every address.
 *
 * <p>Documented opt-in per-request (never global). Wiring this as a default
 * in production should fail loud — it deliberately doesn't read any
 * environment variable or system property so a misconfigured deploy can't
 * silently disable the guard.
 */
final class PermissiveSsrfPolicy implements SsrfPolicy {

    static final PermissiveSsrfPolicy INSTANCE = new PermissiveSsrfPolicy();

    private PermissiveSsrfPolicy() {}

    @Override
    public SsrfDecision evaluate(InetAddress address) {
        return SsrfDecision.ALLOW;
    }
}
