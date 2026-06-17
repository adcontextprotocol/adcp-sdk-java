package org.adcontextprotocol.adcp.signing;

import org.jspecify.annotations.Nullable;

/**
 * Result of key resolution for inbound verification. Either a key was found
 * (with optional tenant/principal metadata) or the {@code kid} is unknown.
 *
 * <p>Per the signing-context spec: if {@link Found} returns {@code null}
 * tenant, receivers must treat the request as untenanted and reject
 * tenant-scoped operations. They must not recover tenant identity from
 * unsigned body, header, or query fields.
 */
public sealed interface VerificationKeyLookup {

    /**
     * A verification key was found, along with any tenant/principal metadata
     * discovered during key lookup.
     *
     * @param key       the verification key
     * @param tenant    resolved tenant, or {@code null} if untenanted
     * @param principal resolved principal, or {@code null} if not available
     */
    record Found(VerificationKey key, @Nullable TenantId tenant, @Nullable PrincipalRef principal)
            implements VerificationKeyLookup {
        public Found {
            if (key == null) throw new NullPointerException("key");
        }
    }

    /**
     * No key found for the given {@code kid}.
     *
     * @param kid the key identifier that could not be resolved
     */
    record Missing(String kid) implements VerificationKeyLookup {
        public Missing {
            if (kid == null) throw new NullPointerException("kid");
        }
    }
}