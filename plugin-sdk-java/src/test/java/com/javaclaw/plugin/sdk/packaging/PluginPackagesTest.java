package com.javaclaw.plugin.sdk.packaging;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class PluginPackagesTest {
    @TempDir Path temp;
    @Test void createsCanonicalHashesAndVerifiablePublisherSignature() throws Exception {
        Path manifest = manifest(), artifact = Files.writeString(temp.resolve("artifact.jar"), "private implementation");
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path output = PluginPackages.create(manifest, artifact, Map.of(), temp.resolve("signed.zip"),
                new PluginPackages.SigningKey(key.getPrivate(), key.getPublic()));
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertEquals(List.of("plugin.jar", "plugin.json", "files.sha256", "signature.json"),
                    zip.stream().map(java.util.zip.ZipEntry::getName).toList());
            byte[] list = zip.getInputStream(zip.getEntry("files.sha256")).readAllBytes();
            for (String line : new String(list, StandardCharsets.UTF_8).lines().toList()) {
                byte[] bytes = zip.getInputStream(zip.getEntry(line.substring(66))).readAllBytes();
                assertEquals(line.substring(0, 64), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
            var metadata = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("signature.json"))
                    .readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(metadata.get("publicKey").getAsString()))));
            verifier.update(list);
            assertTrue(verifier.verify(Base64.getDecoder().decode(metadata.get("signature").getAsString())));
        }
    }
    @Test void unsignedPackagesStillHashEveryFileAndRejectTraversalOrReservedEntries() throws Exception {
        Path manifest = manifest(), artifact = Files.writeString(temp.resolve("artifact.jar"), "implementation");
        Path output = PluginPackages.create(manifest, artifact, Map.of(), temp.resolve("unsigned.zip"), null);
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertNull(zip.getEntry("signature.json")); assertNotNull(zip.getEntry("files.sha256"));
        }
        for (String name : List.of("../outside", "/absolute", "lib//artifact.jar", "signature.json", "plugin.json")) {
            assertThrows(IllegalArgumentException.class, () -> PluginPackages.create(manifest, artifact,
                    Map.of(name, artifact), output, null));
        }
        KeyPair wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(IllegalArgumentException.class, () -> PluginPackages.create(manifest, artifact,
                Map.of(), output, new PluginPackages.SigningKey(wrong.getPrivate(), other.getPublic())));
    }
    @Test void readsPublisherKeyFilesWithoutIncludingThemInThePackage() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path privateKey = Files.writeString(temp.resolve("publisher-private.pem"), "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        Path publicKey = Files.write(temp.resolve("publisher-public.der"), key.getPublic().getEncoded());
        Path artifact = Files.writeString(temp.resolve("artifact.jar"), "implementation");
        Path output = PluginPackages.create(manifest(), artifact, Map.of(), temp.resolve("signed-keys.zip"),
                PluginPackages.SigningKey.read(privateKey, publicKey));
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertEquals(4, zip.size()); assertNull(zip.getEntry("publisher-private.pem"));
            assertNotNull(zip.getEntry("signature.json"));
        }
    }
    private Path manifest() throws Exception { return Files.writeString(temp.resolve("manifest.json"),
            "{\"id\":\"test\",\"protocol\":{\"major\":2,\"minor\":0},\"entrypoint\":\"test.Plugin\",\"artifact\":\"plugin.jar\"}"); }
}
