package org.adcontextprotocol.adcp.server.signing.jws;

import org.adcontextprotocol.adcp.server.signing.InProcessKeyGenerator;
import org.adcontextprotocol.adcp.signing.VerificationKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwsVerifierTest {

    private KeyPair ed25519KeyPair;
    private VerificationKey ed25519VerificationKey;

    @BeforeEach
    void setUp() throws Exception {
        ed25519KeyPair = InProcessKeyGenerator.generateEd25519();
        ed25519VerificationKey = new VerificationKey(
                "test-kid-ed25519",
                "Ed25519",
                ed25519KeyPair.getPublic().getEncoded(),
                null);
    }

    @Test
    void parse_compactJws_threeParts() {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"EdDSA\",\"kid\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"https://example.com\"}".getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[64]);

        JwsDocument doc = JwsVerifier.parse(header + "." + payload + "." + sig);
        assertEquals(header, doc.b64ProtectedHeader());
        assertEquals(payload, doc.b64Payload());
        assertArrayEquals(new byte[64], doc.signature());
    }

    @Test
    void parse_compactJws_wrongPartCount_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> JwsVerifier.parse("header.payload"));
        assertThrows(IllegalArgumentException.class,
                () -> JwsVerifier.parse("a.b.c.d"));
    }

    @Test
    void parse_compactJws_emptySegment_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> JwsVerifier.parse(".payload.c2ln"));
    }

    @Test
    void parse_jsonGeneralJws_valid() {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"EdDSA\",\"kid\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"https://example.com\"}".getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[64]);

        String json = "{\"payload\":\"" + payload + "\",\"signatures\":[{\"protected\":\""
                + header + "\",\"signature\":\"" + sig + "\"}]}";
        JwsDocument doc = JwsVerifier.parse(json);
        assertEquals(header, doc.b64ProtectedHeader());
        assertEquals(payload, doc.b64Payload());
    }

    @Test
    void parse_jsonGeneralJws_multipleSignatures_throws() {
        String json = "{\"payload\":\"cGF5bG9hZA\",\"signatures\":"
                + "[{\"protected\":\"a\",\"signature\":\"b\"},{\"protected\":\"c\",\"signature\":\"d\"}]}";
        assertThrows(IllegalArgumentException.class, () -> JwsVerifier.parse(json));
    }

    @Test
    void verify_validCompactJws_ed25519() throws Exception {
        String headerJson = "{\"alg\":\"EdDSA\",\"kid\":\"test-kid-ed25519\",\"typ\":\"adcp-gov-revocation+jws\"}";
        String b64Header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadJson = "{\"iss\":\"https://example.com\",\"revoked_kids\":[]}";
        String b64Payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

        String signingInput = b64Header + "." + b64Payload;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(ed25519KeyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();
        String b64Signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        String compactJws = b64Header + "." + b64Payload + "." + b64Signature;

        JwsVerificationResult result = JwsVerifier.verify(compactJws, ed25519VerificationKey,
                "adcp-gov-revocation+jws");
        assertInstanceOf(JwsVerificationResult.Valid.class, result);
        JwsVerificationResult.Valid valid = (JwsVerificationResult.Valid) result;
        assertTrue(valid.payload().contains("example.com"));
        assertEquals("test-kid-ed25519", valid.kid());
    }

    @Test
    void verify_wrongKey_returnsInvalid() throws Exception {
        String headerJson = "{\"alg\":\"EdDSA\",\"kid\":\"test-kid-ed25519\"}";
        String b64Header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String b64Payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"https://example.com\"}".getBytes(StandardCharsets.UTF_8));

        String signingInput = b64Header + "." + b64Payload;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(ed25519KeyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();
        String b64Signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        String compactJws = b64Header + "." + b64Payload + "." + b64Signature;

        KeyPair wrongKeyPair = InProcessKeyGenerator.generateEd25519();
        VerificationKey wrongKey = new VerificationKey(
                "test-kid-ed25519", "Ed25519", wrongKeyPair.getPublic().getEncoded(), null);

        JwsVerificationResult result = JwsVerifier.verify(compactJws, wrongKey);
        assertInstanceOf(JwsVerificationResult.Invalid.class, result);
        assertTrue(((JwsVerificationResult.Invalid) result).reason().contains("Signature verification failed"));
    }

    @Test
    void verify_algNone_returnsInvalid() {
        String headerJson = "{\"alg\":\"none\",\"kid\":\"test\"}";
        String b64Header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String b64Payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes(StandardCharsets.UTF_8));
        String b64Signature = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);

        String compactJws = b64Header + "." + b64Payload + "." + b64Signature;

        JwsVerificationResult result = JwsVerifier.verify(compactJws, ed25519VerificationKey);
        assertInstanceOf(JwsVerificationResult.Invalid.class, result);
        assertTrue(((JwsVerificationResult.Invalid) result).reason().contains("not allowed"));
    }

    @Test
    void verify_typMismatch_returnsInvalid() throws Exception {
        String headerJson = "{\"alg\":\"EdDSA\",\"kid\":\"test-kid-ed25519\",\"typ\":\"wrong-typ\"}";
        String b64Header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String b64Payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes(StandardCharsets.UTF_8));

        String signingInput = b64Header + "." + b64Payload;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(ed25519KeyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();
        String b64Signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        String compactJws = b64Header + "." + b64Payload + "." + b64Signature;

        JwsVerificationResult result = JwsVerifier.verify(compactJws, ed25519VerificationKey,
                "adcp-gov-revocation+jws");
        assertInstanceOf(JwsVerificationResult.Invalid.class, result);
        assertTrue(((JwsVerificationResult.Invalid) result).reason().contains("typ"));
    }

    @Test
    void verify_missingKid_returnsInvalid() throws Exception {
        String headerJson = "{\"alg\":\"EdDSA\"}";
        String b64Header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String b64Payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes(StandardCharsets.UTF_8));

        String signingInput = b64Header + "." + b64Payload;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(ed25519KeyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();
        String b64Signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        String compactJws = b64Header + "." + b64Payload + "." + b64Signature;

        JwsVerificationResult result = JwsVerifier.verify(compactJws, ed25519VerificationKey);
        assertInstanceOf(JwsVerificationResult.Invalid.class, result);
        assertTrue(((JwsVerificationResult.Invalid) result).reason().contains("kid"));
    }

    @Test
    void verify_tamperedSignature_returnsInvalid() throws Exception {
        String headerJson = "{\"alg\":\"EdDSA\",\"kid\":\"test-kid-ed25519\"}";
        String b64Header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String b64Payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes(StandardCharsets.UTF_8));

        String signingInput = b64Header + "." + b64Payload;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(ed25519KeyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();
        signature[0] = (byte) (signature[0] ^ 0xFF);
        String b64Signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        String compactJws = b64Header + "." + b64Payload + "." + b64Signature;

        JwsVerificationResult result = JwsVerifier.verify(compactJws, ed25519VerificationKey);
        assertInstanceOf(JwsVerificationResult.Invalid.class, result);
    }
}