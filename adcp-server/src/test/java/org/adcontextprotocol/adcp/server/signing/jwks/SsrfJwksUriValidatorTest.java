package org.adcontextprotocol.adcp.server.signing.jwks;

import org.adcontextprotocol.adcp.http.SsrfBlockedException;
import org.adcontextprotocol.adcp.http.SsrfPolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class SsrfJwksUriValidatorTest {

    @Test
    void allowsPublicHttpsUri() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator(SsrfPolicy.strict(), true);
        assertDoesNotThrow(() -> validator.validate(
                URI.create("https://example.com/.well-known/jwks.json")));
    }

    @Test
    void rejectsPrivateIp() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator(SsrfPolicy.strict(), true);
        assertThrows(SsrfBlockedException.class, () -> validator.validate(
                URI.create("https://192.168.1.1/.well-known/jwks.json")));
    }

    @Test
    void rejectsLocalhost() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator(SsrfPolicy.strict(), true);
        assertThrows(SsrfBlockedException.class, () -> validator.validate(
                URI.create("https://127.0.0.1/.well-known/jwks.json")));
    }

    @Test
    void rejectsLinkLocal() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator(SsrfPolicy.strict(), true);
        assertThrows(SsrfBlockedException.class, () -> validator.validate(
                URI.create("https://169.254.169.254/.well-known/jwks.json")));
    }

    @Test
    void rejectsCloudMetadata() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator(SsrfPolicy.strict(), true);
        assertThrows(SsrfBlockedException.class, () -> validator.validate(
                URI.create("http://169.254.169.254/latest/meta-data/")));
    }

    @Test
    void rejectsAlibabaCloudMetadata() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator(SsrfPolicy.strict(), true);
        assertThrows(SsrfBlockedException.class, () -> validator.validate(
                URI.create("http://100.100.100.200/latest/meta-data/")));
    }

    @Test
    void rejectsNonHttpsInProduction() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator(SsrfPolicy.strict(), true);
        assertThrows(SsrfBlockedException.class, () -> validator.validate(
                URI.create("http://example.com/.well-known/jwks.json")));
    }

    @Test
    void allowsHttpWithPermissivePolicy() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator(SsrfPolicy.permissive(), false);
        assertDoesNotThrow(() -> validator.validate(
                URI.create("http://example.com/.well-known/jwks.json")));
    }

    @Test
    void rejectsInvalidScheme() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator();
        assertThrows(SsrfBlockedException.class, () -> validator.validate(
                URI.create("ftp://example.com/jwks.json")));
    }

    @Test
    void rejectsMulticastAddress() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator(SsrfPolicy.strict(), true);
        assertThrows(SsrfBlockedException.class, () -> validator.validate(
                URI.create("https://224.0.0.1/.well-known/jwks.json")));
    }

    @Test
    void rejectsReservedClassE() {
        SsrfJwksUriValidator validator = new SsrfJwksUriValidator(SsrfPolicy.strict(), true);
        assertThrows(SsrfBlockedException.class, () -> validator.validate(
                URI.create("https://240.0.0.1/.well-known/jwks.json")));
    }
}