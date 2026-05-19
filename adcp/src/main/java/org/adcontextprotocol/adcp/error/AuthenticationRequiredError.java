package org.adcontextprotocol.adcp.error;

import org.adcontextprotocol.adcp.auth.AuthChallengeInfo;
import org.adcontextprotocol.adcp.auth.OAuthMetadataInfo;
import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * The agent requires authentication. Carries parsed {@code WWW-Authenticate}
 * challenge info and optional OAuth metadata for programmatic auth flows.
 *
 * <p>The {@link #challenge()} field is populated on a best-effort basis via
 * a HEAD (or OPTIONS) probe when a 401 is detected. It may be {@code null}
 * even when authentication is genuinely required — for example, if the
 * agent endpoint returns 405 for both HEAD and OPTIONS, or if the probe
 * itself fails. Callers should not assume a {@code null} challenge means
 * "no auth needed"; it means the auth scheme could not be determined.
 */
public final class AuthenticationRequiredError extends AdcpError {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final URI agentUri;
    @SuppressWarnings("serial")
    private final @Nullable AuthChallengeInfo challenge;
    @SuppressWarnings("serial")
    private final @Nullable OAuthMetadataInfo oauthMetadata;

    public AuthenticationRequiredError(
            URI agentUri,
            @Nullable AuthChallengeInfo challenge,
            @Nullable OAuthMetadataInfo oauthMetadata) {
        this(agentUri, challenge, oauthMetadata, null);
    }

    public AuthenticationRequiredError(
            URI agentUri,
            @Nullable AuthChallengeInfo challenge,
            @Nullable OAuthMetadataInfo oauthMetadata,
            @Nullable Throwable cause) {
        super("AUTHENTICATION_REQUIRED",
                "Authentication required for agent: " + agentUri,
                null, cause);
        this.agentUri = agentUri;
        this.challenge = challenge;
        this.oauthMetadata = oauthMetadata;
    }

    public URI agentUri() {
        return agentUri;
    }

    public @Nullable AuthChallengeInfo challenge() {
        return challenge;
    }

    public @Nullable OAuthMetadataInfo oauthMetadata() {
        return oauthMetadata;
    }

    public boolean hasOAuth() {
        return oauthMetadata != null;
    }

    /** The suggested auth scheme (lowercased), or {@code null} if unknown. */
    public @Nullable String suggestedScheme() {
        return challenge != null ? challenge.scheme() : null;
    }
}
