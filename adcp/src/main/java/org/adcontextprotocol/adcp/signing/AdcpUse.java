package org.adcontextprotocol.adcp.signing;

/**
 * The AdCP signing key purpose, matching the {@code adcp_use} JWK parameter.
 *
 * <p>Each signing key is provisioned for exactly one purpose. Verification
 * enforces that the key's {@code adcp_use} matches the expected operation.
 */
public enum AdcpUse {
    REQUEST_SIGNING("adcp_req"),
    WEBHOOK_SIGNING("adcp_whk");

    private final String wireName;

    AdcpUse(String wireName) {
        this.wireName = wireName;
    }

    /** Wire name matching the {@code adcp_use} JWK parameter value. */
    public String wireName() {
        return wireName;
    }

    /**
     * Resolve an {@link AdcpUse} from its wire name.
     *
     * <p>Accepts both the short AdCP wire names ({@code adcp_req}, {@code adcp_whk})
     * and the long-form names used in published conformance vectors
     * ({@code request-signing}, {@code webhook-signing}).
     *
     * @param wireName the {@code adcp_use} JWK value
     * @return the matching {@link AdcpUse}
     * @throws IllegalArgumentException if the wire name is unrecognized
     */
    public static AdcpUse fromWireName(String wireName) {
        if (wireName == null) {
            throw new NullPointerException("wireName");
        }
        for (AdcpUse use : values()) {
            if (use.wireName.equals(wireName)) {
                return use;
            }
        }
        // Accept long-form names from published conformance vectors.
        return switch (wireName) {
            case "request-signing" -> REQUEST_SIGNING;
            case "webhook-signing" -> WEBHOOK_SIGNING;
            default -> throw new IllegalArgumentException("Unknown adcp_use: " + wireName);
        };
    }
}