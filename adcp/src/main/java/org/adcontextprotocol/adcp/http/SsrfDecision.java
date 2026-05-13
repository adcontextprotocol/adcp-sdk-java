package org.adcontextprotocol.adcp.http;

/**
 * Outcome of an {@link SsrfPolicy} check against a candidate target address.
 * Sealed so call sites pattern-match against the two cases without a
 * default branch.
 */
public sealed interface SsrfDecision {

    /** The address is allowed; the request may proceed. */
    record Allow() implements SsrfDecision {}

    /**
     * The address is blocked. {@code reason} is a short, log-friendly
     * description; safe to include in error envelopes since it never
     * carries the rejected address itself (which could leak host
     * structure to an attacker).
     */
    record Deny(String reason) implements SsrfDecision {}

    /** Singleton {@link Allow} instance. */
    Allow ALLOW = new Allow();
}
