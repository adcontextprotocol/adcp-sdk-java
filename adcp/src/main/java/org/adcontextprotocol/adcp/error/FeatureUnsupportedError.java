package org.adcontextprotocol.adcp.error;

import java.util.List;

/** The agent lacks features required by the caller. */
public final class FeatureUnsupportedError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial")
    private final List<String> unsupportedFeatures;
    @SuppressWarnings("serial")
    private final List<String> declaredFeatures;

    public FeatureUnsupportedError(
            List<String> unsupportedFeatures,
            List<String> declaredFeatures) {
        super("FEATURE_UNSUPPORTED",
                "Unsupported features: " + unsupportedFeatures,
                null);
        this.unsupportedFeatures = List.copyOf(unsupportedFeatures);
        this.declaredFeatures = List.copyOf(declaredFeatures);
    }

    public List<String> unsupportedFeatures() {
        return unsupportedFeatures;
    }

    public List<String> declaredFeatures() {
        return declaredFeatures;
    }
}
