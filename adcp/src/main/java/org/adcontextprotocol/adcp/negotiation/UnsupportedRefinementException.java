package org.adcontextprotocol.adcp.negotiation;

/** Preflight failure for a typed dimension omitted by seller capabilities. */
public final class UnsupportedRefinementException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final transient UnsupportedRefinementDetails details;

    public UnsupportedRefinementException(UnsupportedRefinementDetails details) {
        super("unsupported refinement dimension: " + details.unsupportedDimension());
        this.details = details;
    }

    public UnsupportedRefinementDetails details() {
        return details;
    }
}
