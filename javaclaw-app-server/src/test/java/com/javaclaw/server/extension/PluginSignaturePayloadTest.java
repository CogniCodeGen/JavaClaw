package com.javaclaw.server.extension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSignaturePayloadTest {
    @TempDir
    Path temporary;

    @Test
    void verifiesCanonicalManifestAndSortedBundleDigestList() throws Exception {
        Path bundle = temporary.resolve("signed");
        Files.createDirectories(bundle.resolve(".javaclaw-plugin"));
        Files.writeString(bundle.resolve("payload.txt"), "signed content");
        Path manifest = bundle.resolve(".javaclaw-plugin/plugin.json");
        String template = """
                {"apiVersion":4,"minimumProtocolVersion":1,"id":"signed.plugin",
                 "version":"1.0.0","name":"Signed","processes":[],"skills":[],
                 "signature":{"algorithm":"Ed25519","keyId":"publisher",
                              "value":"%s"}}
                """;
        Files.writeString(manifest, template.formatted("placeholder"));
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(PluginSignaturePayload.create(Files.readAllBytes(manifest), bundle));
        Files.writeString(manifest, template.formatted(Base64.getEncoder().encodeToString(signer.sign())));

        PluginTrustStore trust = new PluginTrustStore() {
            final TrustedKey key =
                    new TrustedKey("publisher", keys.getPublic().getEncoded(), "Publisher", 1, Instant.now());

            @Override
            public List<TrustedKey> list() {
                return List.of(key);
            }

            @Override
            public Optional<TrustedKey> find(String keyId) {
                return "publisher".equals(keyId) ? Optional.of(key) : Optional.empty();
            }

            @Override
            public TrustedKey add(String keyId, byte[] value, String label, long revision, String idempotencyKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean remove(String keyId, long revision, String key) {
                throw new UnsupportedOperationException();
            }
        };
        LoadedPlugin loaded = new PluginBundleLoader(new Ed25519PluginSignatureVerifier(trust)).load(bundle);
        assertTrue(loaded.signatureVerified());
    }
}
