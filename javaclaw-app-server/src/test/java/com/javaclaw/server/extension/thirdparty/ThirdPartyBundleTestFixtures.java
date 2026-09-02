package com.javaclaw.server.extension.thirdparty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ViewSchemaWireCodec;
import com.javaclaw.server.persistence.ExtensionTrustKeyRecord;

final class ThirdPartyBundleTestFixtures {
    static final String EXTENSION_ID = "demo.extension";
    static final String KEY_ID = "release-key";
    static final byte[] WORKER = "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8);

    private ThirdPartyBundleTestFixtures() {}

    static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Path archive(Path directory, CanonicalJson json, KeyPair keys) throws Exception {
        ThirdPartyBundleManifest manifest = manifest(json);
        return archive(directory, json, manifest, signature(json, manifest, keys.getPrivate()), WORKER, List.of());
    }

    static Path archiveVersion(Path directory, CanonicalJson json, KeyPair keys, String version) throws Exception {
        ThirdPartyBundleManifest manifest = manifest(json, version);
        return archive(directory, json, manifest, signature(json, manifest, keys.getPrivate()), WORKER, List.of());
    }

    static Path archiveWithInvalidSignature(Path directory, CanonicalJson json, KeyPair keys) throws Exception {
        return archive(directory, json, manifest(json), new byte[64], WORKER, List.of());
    }

    static Path archiveWithExtraFile(Path directory, CanonicalJson json, KeyPair keys) throws Exception {
        ThirdPartyBundleManifest manifest = manifest(json);
        return archive(
                directory,
                json,
                manifest,
                signature(json, manifest, keys.getPrivate()),
                WORKER,
                List.of(new ExtraEntry("extra.txt", "unsigned".getBytes(StandardCharsets.UTF_8))));
    }

    static Path traversalArchive(Path directory) throws IOException {
        Path archive = directory.resolve("traversal.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "../escape", new byte[] {1});
        }
        return archive;
    }

    static AttachmentContent attachment(Path archive) throws IOException {
        byte[] content = Files.readAllBytes(archive);
        return new AttachmentContent(
                new AttachmentMetadata(
                        digest(content),
                        com.javaclaw.protocol.BundleRpcContracts.BUNDLE_MEDIA_TYPE,
                        content.length,
                        Instant.parse("2026-09-01T00:00:00Z")),
                content);
    }

    static ExtensionTrustedKeys trustedKeys(KeyPair keys) {
        byte[] encoded = keys.getPublic().getEncoded();
        return ExtensionTrustedKeys.from(List.of(ExtensionTrustKeyRecord.active(
                KEY_ID, digest(encoded), digest(encoded), encoded, Instant.parse("2026-09-01T00:00:00Z"))));
    }

    static ExtensionTrustedKeys noTrustedKeys() {
        return ExtensionTrustedKeys.from(List.of());
    }

    static ThirdPartyBundleManifest manifest(CanonicalJson json) {
        return manifest(json, "5.0.0");
    }

    private static ThirdPartyBundleManifest manifest(CanonicalJson json, String version) {
        CanonicalPayload objectSchema = json.parse("{\"properties\":{},\"type\":\"object\"}");
        ThirdPartyToolDefinition tool = new ThirdPartyToolDefinition(
                "demo_lookup", "查询示例数据", objectSchema, objectSchema, ToolRisk.READ_ONLY, Set.of("demo"));
        ViewSchema view = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "demo.main",
                "示例扩展",
                List.of(),
                List.of(new ViewSchema.Card("card", "示例", "只读页面", List.of())));
        return new ThirdPartyBundleManifest(
                ThirdPartyBundleManifest.FORMAT_VERSION,
                EXTENSION_ID,
                "Demo Extension",
                version,
                KEY_ID,
                new ThirdPartyBundleManifest.EntryPoint("bin/worker", List.of("--worker")),
                new ThirdPartyBundleManifest.PermissionRequest(
                        true,
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        true,
                        Duration.ofSeconds(10),
                        new ResourceLimits(64L * 1024 * 1024, 64L * 1024, 1, 16),
                        ToolRisk.READ_ONLY),
                List.of(
                        new ThirdPartyBundleManifest.Contribution(
                                "query", ContributionKind.QUERY, Set.of("status"), Optional.empty()),
                        new ThirdPartyBundleManifest.Contribution(
                                "command", ContributionKind.COMMAND, Set.of("put"), Optional.empty()),
                        new ThirdPartyBundleManifest.Contribution(
                                "tool", ContributionKind.TOOL, Set.of(), Optional.of(json.encode(tool))),
                        new ThirdPartyBundleManifest.Contribution(
                                "view",
                                ContributionKind.VIEW,
                                Set.of(),
                                Optional.of(new ViewSchemaWireCodec(json).encode(view))),
                        new ThirdPartyBundleManifest.Contribution(
                                "skill",
                                ContributionKind.SKILL,
                                Set.of(),
                                Optional.of(json.parse("{\"name\":\"demo\"}")))),
                List.of(new ThirdPartyBundleManifest.SchemaDocument("demo/schema@1", objectSchema)),
                List.of(new ThirdPartyBundleManifest.BundleFile("bin/worker", digest(WORKER), WORKER.length)));
    }

    private static Path archive(
            Path directory,
            CanonicalJson json,
            ThirdPartyBundleManifest manifest,
            byte[] manifestSignature,
            byte[] worker,
            List<ExtraEntry> extras)
            throws Exception {
        byte[] manifestBytes = json.encode(manifest).json().getBytes(StandardCharsets.UTF_8);
        Path archive = Files.createTempFile(directory, "bundle-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "manifest.json", manifestBytes);
            put(zip, "manifest.sig", Base64.getEncoder().encode(manifestSignature));
            put(zip, "bin/worker", worker);
            for (ExtraEntry extra : extras) {
                put(zip, extra.name(), extra.content());
            }
        }
        return archive;
    }

    private static byte[] signature(CanonicalJson json, ThirdPartyBundleManifest manifest, PrivateKey privateKey)
            throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(json.encode(manifest).json().getBytes(StandardCharsets.UTF_8));
        return signature.sign();
    }

    static String digest(byte[] content) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private record ExtraEntry(String name, byte[] content) {}
}
