package org.adcontextprotocol.adcp.http;

import java.net.InetAddress;

/**
 * SSRF policy for outbound discovery probes.
 *
 * <p>See {@code specs/ssrf-baseline.md} for the threat model and the full
 * block-list. The short version: every outbound HTTP probe resolves DNS once,
 * runs every resolved address through {@link #evaluate(InetAddress)}, and
 * proceeds only if all addresses return {@link SsrfDecision.Allow}.
 *
 * <p>The {@link #strict()} policy is the v0.1 baseline; production builds
 * must use it. {@link #permissive()} exists only for local development
 * against {@code localhost} and is opt-in per-request, never global.
 */
public sealed interface SsrfPolicy permits StrictSsrfPolicy, PermissiveSsrfPolicy {

    /**
     * Evaluate a single resolved address. Implementations should be pure
     * functions of the address — DNS resolution belongs in the caller, which
     * is responsible for pinning the connect to the validated address.
     */
    SsrfDecision evaluate(InetAddress address);

    /**
     * The default policy: denies every range in
     * {@code specs/ssrf-baseline.md} §"Address guards (block list)".
     */
    static SsrfPolicy strict() {
        return StrictSsrfPolicy.INSTANCE;
    }

    /**
     * Permissive policy for local development. Allows every address including
     * loopback, link-local (cloud metadata), and RFC 1918 ranges. Documented
     * opt-in only — never the default. Never wired via env-var or system
     * property so a misconfigured production deploy can't silently disable
     * the guard.
     */
    static SsrfPolicy permissive() {
        return PermissiveSsrfPolicy.INSTANCE;
    }
}
