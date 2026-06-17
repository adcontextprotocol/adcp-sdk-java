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
     * Resolve an {@code AdcpUse} from its wire name.
     *
     * @param wireName the {@code adcp_use} JWK value
     * @return the matching {@code AdcpUse}
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
        throw new IllegalArgumentException("Unknown adcp_use: " + wireName);
    }
}