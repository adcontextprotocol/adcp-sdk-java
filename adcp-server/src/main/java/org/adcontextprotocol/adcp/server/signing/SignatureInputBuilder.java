package org.adcontextprotocol.adcp.server.signing;

import java.util.List;
import java.util.Objects;

/**
 * Builds the RFC 9421 §2.3 {@code Signature-Input} header value string.
 *
 * <p>Produces strings like:
 * <pre>
 * sig1=("@method" "@target-uri" "@authority" "content-type" "content-digest");created=1776520800;expires=1776521100;nonce="KXYnfEfJ0PBRZXQyVXfVQA";keyid="test-ed25519-webhook-2026";alg="ed25519";tag="adcp/webhook-signing/v1"
 * </pre>
 */
public final class SignatureInputBuilder {

    private String label = "sig1";
    private List<String> coveredComponents = List.of();
    private long created;
    private long expires;
    private String nonce = "";
    private String keyid = "";
    private String alg = "";
    private String tag = "";

    private SignatureInputBuilder() {}

    public static SignatureInputBuilder create() {
        return new SignatureInputBuilder();
    }

    public SignatureInputBuilder label(String label) {
        this.label = Objects.requireNonNull(label);
        return this;
    }

    public SignatureInputBuilder coveredComponents(List<String> coveredComponents) {
        this.coveredComponents = Objects.requireNonNull(coveredComponents);
        return this;
    }

    public SignatureInputBuilder created(long created) {
        this.created = created;
        return this;
    }

    public SignatureInputBuilder expires(long expires) {
        this.expires = expires;
        return this;
    }

    public SignatureInputBuilder nonce(String nonce) {
        this.nonce = Objects.requireNonNull(nonce);
        return this;
    }

    public SignatureInputBuilder keyid(String keyid) {
        this.keyid = Objects.requireNonNull(keyid);
        return this;
    }

    public SignatureInputBuilder alg(String alg) {
        this.alg = Objects.requireNonNull(alg);
        return this;
    }

    public SignatureInputBuilder tag(String tag) {
        this.tag = Objects.requireNonNull(tag);
        return this;
    }

    /**
     * Build the Signature-Input header value per RFC 9421 §2.3.
     *
     * <p>Format: {@code label=(component-ids);created=...;expires=...;nonce="...";keyid="...";alg="...";tag="..."}
     *
     * <p>Component identifiers are quoted strings. Pseudo-headers use
     * the {@code @}-prefixed form (e.g. {@code "@method"}).
     * Header field names are lowercased per RFC 9421 §2.1.
     */
    public String build() {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append("=(");
        for (int i = 0; i < coveredComponents.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String component = coveredComponents.get(i);
            sb.append('"').append(component).append('"');
        }
        sb.append(')');
        sb.append(";created=").append(created);
        sb.append(";expires=").append(expires);
        sb.append(";nonce=\"").append(nonce).append('"');
        sb.append(";keyid=\"").append(keyid).append('"');
        sb.append(";alg=\"").append(alg).append('"');
        sb.append(";tag=\"").append(tag).append('"');
        return sb.toString();
    }
}