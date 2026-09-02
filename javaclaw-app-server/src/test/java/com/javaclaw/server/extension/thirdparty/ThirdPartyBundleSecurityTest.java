package com.javaclaw.server.extension.thirdparty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyBundleSecurityTest {
    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private KeyPair keys;
    private ThirdPartyBundleDirectories directories;
    private ThirdPartyBundleArchive archives;

    @BeforeEach
    void initialize() {
        json = new CanonicalJson();
        keys = ThirdPartyBundleTestFixtures.keyPair();
        directories = new ThirdPartyBundleDirectories(
                temporaryDirectory.resolve("data-v5"),
                Clock.fixed(Instant.parse("2026-09-01T03:00:00Z"), ZoneOffset.UTC));
        archives = new ThirdPartyBundleArchive(json, ThirdPartyBundleTestFixtures.trustedKeys(keys), directories);
    }

    @Test
    void archive验签逐文件校验并拒绝安装后篡改() throws Exception {
        Path archive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);

        AttachmentContent attachment = ThirdPartyBundleTestFixtures.attachment(archive);
        VerifiedThirdPartyBundle staged = archives.stage(attachment);
        VerifiedThirdPartyBundle repeated = archives.stage(attachment);

        assertEquals(staged.manifestDigest(), repeated.manifestDigest());
        assertEquals(
                ThirdPartyBundleTestFixtures.EXTENSION_ID, staged.manifest().id());
        assertTrue(Files.isExecutable(staged.root().resolve("bin/worker")));

        Files.writeString(staged.root().resolve("bin/worker"), "tampered", StandardCharsets.UTF_8);
        assertThrows(SecurityException.class, () -> archives.verifyStaged(staged.manifestDigest()));
    }

    @Test
    void archive拒绝未知密钥错误签名和未签名额外文件() throws Exception {
        Path valid = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        Path invalidSignature =
                ThirdPartyBundleTestFixtures.archiveWithInvalidSignature(temporaryDirectory, json, keys);
        Path extraFile = ThirdPartyBundleTestFixtures.archiveWithExtraFile(temporaryDirectory, json, keys);
        ThirdPartyBundleArchive untrusted =
                new ThirdPartyBundleArchive(json, ThirdPartyBundleTestFixtures.noTrustedKeys(), directories);

        assertThrows(SecurityException.class, () -> untrusted.stage(ThirdPartyBundleTestFixtures.attachment(valid)));
        assertThrows(
                SecurityException.class,
                () -> archives.stage(ThirdPartyBundleTestFixtures.attachment(invalidSignature)));
        assertThrows(SecurityException.class, () -> archives.stage(ThirdPartyBundleTestFixtures.attachment(extraFile)));
    }

    @Test
    void zip路径越界在写文件前被拒绝() throws Exception {
        Path archive = ThirdPartyBundleTestFixtures.traversalArchive(temporaryDirectory);
        Path escaped = directories.stagingRoot().getParent().resolve("escape");

        assertThrows(
                IllegalArgumentException.class, () -> archives.stage(ThirdPartyBundleTestFixtures.attachment(archive)));
        assertFalse(Files.exists(escaped));
    }

    @Test
    void archive拒绝错误媒体类型摘要空内容和伪造staging身份() throws Exception {
        AttachmentContent valid = ThirdPartyBundleTestFixtures.attachment(
                ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys));
        AttachmentMetadata metadata = valid.metadata();
        AttachmentContent wrongMedia = new AttachmentContent(
                new AttachmentMetadata(
                        metadata.digest(), "application/zip", metadata.sizeBytes(), metadata.createdAt()),
                valid.content());
        AttachmentContent wrongDigest = new AttachmentContent(
                new AttachmentMetadata(
                        "0".repeat(64), metadata.mediaType(), metadata.sizeBytes(), metadata.createdAt()),
                valid.content());
        AttachmentContent empty = new AttachmentContent(
                new AttachmentMetadata(
                        ThirdPartyBundleTestFixtures.digest(new byte[0]),
                        BundleRpcContracts.BUNDLE_MEDIA_TYPE,
                        0,
                        metadata.createdAt()),
                new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> archives.stage(wrongMedia));
        assertThrows(SecurityException.class, () -> archives.stage(wrongDigest));
        assertThrows(IllegalArgumentException.class, () -> archives.stage(empty));
        assertThrows(IllegalArgumentException.class, () -> archives.verifyStaged("bad"));
    }

    @Test
    void staging和installed目录必须匹配已审阅Manifest摘要() throws Exception {
        VerifiedThirdPartyBundle staged = archives.stage(ThirdPartyBundleTestFixtures.attachment(
                ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys)));
        String forgedStagingId = "f".repeat(64);
        Files.move(staged.root(), directories.staging(forgedStagingId));

        assertThrows(SecurityException.class, () -> archives.verifyStaged(forgedStagingId));

        ArchiveHarness installedHarness = archiveHarness("installed-digest");
        VerifiedThirdPartyBundle installedStage = installedHarness
                .archives()
                .stage(ThirdPartyBundleTestFixtures.attachment(
                        ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys)));
        Path installed = installedHarness.directories().install(installedStage.root(), "demo-r1");

        assertThrows(
                SecurityException.class, () -> installedHarness.archives().verifyInstalled(installed, "e".repeat(64)));
    }

    @Test
    void bundle元数据拒绝非法Base64空文件目录和内部符号链接() throws Exception {
        ArchiveHarness invalidSignature = stagedHarness("invalid-signature");
        Files.writeString(invalidSignature.stagedRoot().resolve("manifest.sig"), "%%%", StandardCharsets.US_ASCII);
        assertThrows(
                SecurityException.class, () -> invalidSignature.archives().verifyStaged(invalidSignature.stagingId()));

        ArchiveHarness emptyManifest = stagedHarness("empty-manifest");
        Files.write(emptyManifest.stagedRoot().resolve("manifest.json"), new byte[0]);
        assertThrows(SecurityException.class, () -> emptyManifest.archives().verifyStaged(emptyManifest.stagingId()));

        ArchiveHarness manifestDirectory = stagedHarness("manifest-directory");
        Files.delete(manifestDirectory.stagedRoot().resolve("manifest.json"));
        Files.createDirectory(manifestDirectory.stagedRoot().resolve("manifest.json"));
        assertThrows(
                SecurityException.class,
                () -> manifestDirectory.archives().verifyStaged(manifestDirectory.stagingId()));

        ArchiveHarness symbolicWorker = stagedHarness("symbolic-worker");
        Path worker = symbolicWorker.stagedRoot().resolve("bin/worker");
        Files.delete(worker);
        Files.createSymbolicLink(worker, temporaryDirectory.resolve("outside-worker"));
        assertThrows(SecurityException.class, () -> symbolicWorker.archives().verifyStaged(symbolicWorker.stagingId()));
    }

    @Test
    void zip解压拒绝过多条目和超大Manifest并安全处理目录条目() throws Exception {
        Path tooMany = archiveWithEntries("too-many.zip", 513, false, 1);
        Path oversizedManifest = archiveWithEntries("oversized-manifest.zip", 1, false, 1024 * 1024 + 1);
        Path directoryEntry = archiveWithEntries("directory-entry.zip", 1, true, 0);

        assertThrows(SecurityException.class, () -> archives.stage(ThirdPartyBundleTestFixtures.attachment(tooMany)));
        assertThrows(
                SecurityException.class,
                () -> archiveHarness("oversized")
                        .archives()
                        .stage(ThirdPartyBundleTestFixtures.attachment(oversizedManifest)));
        assertThrows(
                SecurityException.class,
                () -> archiveHarness("directory")
                        .archives()
                        .stage(ThirdPartyBundleTestFixtures.attachment(directoryEntry)));
    }

    @Test
    void manifest拒绝基础字段与入口歧义() {
        ThirdPartyBundleManifest valid = ThirdPartyBundleTestFixtures.manifest(json);
        var entry = valid.entryPoint();
        var permission = valid.permissions();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest(
                        2,
                        valid.id(),
                        valid.displayName(),
                        valid.version(),
                        valid.signingKeyId(),
                        entry,
                        permission,
                        valid.contributions(),
                        valid.schemas(),
                        valid.files()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest(
                        1,
                        "Bad/Id",
                        valid.displayName(),
                        valid.version(),
                        valid.signingKeyId(),
                        entry,
                        permission,
                        valid.contributions(),
                        valid.schemas(),
                        valid.files()));
        assertThrows(
                IllegalArgumentException.class, () -> new ThirdPartyBundleManifest.EntryPoint("../worker", List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> new ThirdPartyBundleManifest.EntryPoint("worker", List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> new ThirdPartyBundleManifest.BundleFile("worker", "bad", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest.BundleFile("worker", "a".repeat(64), -1));
    }

    @Test
    void manifest拒绝贡献与权限歧义() {
        ThirdPartyBundleManifest valid = ThirdPartyBundleTestFixtures.manifest(json);
        var permission = valid.permissions();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest.PermissionRequest(
                        false,
                        true,
                        false,
                        Set.of(),
                        Set.of(),
                        true,
                        Duration.ofSeconds(1),
                        permission.resources(),
                        ToolRisk.READ_ONLY));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest.PermissionRequest(
                        true,
                        false,
                        true,
                        Set.of(),
                        Set.of(),
                        true,
                        Duration.ofSeconds(1),
                        permission.resources(),
                        ToolRisk.READ_ONLY));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest.Contribution(
                        "query", ContributionKind.QUERY, Set.of(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest.Contribution(
                        "tool", ContributionKind.TOOL, Set.of("invalid"), Optional.of(json.parse("{}"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyBundleManifest.Contribution(
                        "tool", ContributionKind.TOOL, Set.of(), Optional.empty()));
    }

    @Test
    void manifest拒绝超过平台资源上限() {
        ThirdPartyBundleManifest valid = ThirdPartyBundleTestFixtures.manifest(json);
        var entry = valid.entryPoint();
        var file = valid.files().getFirst();

        ThirdPartyBundleManifest excessive = new ThirdPartyBundleManifest(
                1,
                valid.id(),
                valid.displayName(),
                valid.version(),
                valid.signingKeyId(),
                entry,
                new ThirdPartyBundleManifest.PermissionRequest(
                        true,
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        true,
                        Duration.ofMinutes(3),
                        new ResourceLimits(1024, 1024, 1, 1),
                        ToolRisk.READ_ONLY),
                valid.contributions(),
                valid.schemas(),
                List.of(file));
        assertThrows(IllegalArgumentException.class, () -> ThirdPartyPermissionPolicy.validate(excessive, json));
    }

    @Test
    void permission策略分别拒绝资源越界与非精确网络端口() {
        ThirdPartyBundleManifest valid = ThirdPartyBundleTestFixtures.manifest(json);
        var request = valid.permissions();
        ThirdPartyBundleManifest excessiveResources = withPermission(
                valid,
                new ThirdPartyBundleManifest.PermissionRequest(
                        request.workspaceRead(),
                        request.workspaceWrite(),
                        request.allowDelete(),
                        request.networkHosts(),
                        request.networkPorts(),
                        request.tlsOnly(),
                        request.maxRunTime(),
                        new ResourceLimits(512L * 1024 * 1024 + 1, 1024, 1, 1),
                        request.maximumToolRisk()));
        ThirdPartyBundleManifest wildcardPort = withPermission(
                valid,
                new ThirdPartyBundleManifest.PermissionRequest(
                        request.workspaceRead(),
                        request.workspaceWrite(),
                        request.allowDelete(),
                        Set.of("api.example.com"),
                        Set.of(NetworkPermission.ANY_PORT),
                        true,
                        request.maxRunTime(),
                        request.resources(),
                        request.maximumToolRisk()));

        assertThrows(
                IllegalArgumentException.class, () -> ThirdPartyPermissionPolicy.validate(excessiveResources, json));
        assertThrows(IllegalArgumentException.class, () -> ThirdPartyPermissionPolicy.validate(wildcardPort, json));
    }

    @Test
    void permission策略拒绝超出Manifest申请上限的工具风险() {
        ThirdPartyBundleManifest valid = ThirdPartyBundleTestFixtures.manifest(json);
        ThirdPartyToolDefinition elevated = new ThirdPartyToolDefinition(
                "dangerous",
                "高风险工具",
                json.parse("{\"type\":\"object\"}"),
                json.parse("{\"type\":\"object\"}"),
                ToolRisk.PROCESS,
                Set.of());
        ThirdPartyBundleManifest.Contribution elevatedContribution = new ThirdPartyBundleManifest.Contribution(
                "tool", ContributionKind.TOOL, Set.of(), Optional.of(json.encode(elevated)));
        List<ThirdPartyBundleManifest.Contribution> contributions = valid.contributions().stream()
                .map(contribution -> contribution.kind() == ContributionKind.TOOL ? elevatedContribution : contribution)
                .toList();
        ThirdPartyBundleManifest elevatedManifest = new ThirdPartyBundleManifest(
                valid.formatVersion(),
                valid.id(),
                valid.displayName(),
                valid.version(),
                valid.signingKeyId(),
                valid.entryPoint(),
                valid.permissions(),
                contributions,
                valid.schemas(),
                valid.files());

        assertThrows(IllegalArgumentException.class, () -> ThirdPartyPermissionPolicy.validate(elevatedManifest, json));
    }

    @Test
    void broker权限取已审阅Manifest与调用方实时权限交集() {
        ThirdPartyBundleManifest valid = ThirdPartyBundleTestFixtures.manifest(json);
        ThirdPartyBundleManifest networked = withNetworkPermission(
                valid,
                new ThirdPartyBundleManifest.PermissionRequest(
                        true,
                        false,
                        false,
                        Set.of("api.example.com", "files.example.com"),
                        Set.of(443, 8443),
                        true,
                        Duration.ofSeconds(10),
                        valid.permissions().resources(),
                        ToolRisk.READ_ONLY));
        PermissionProfile caller = new PermissionProfile(
                "caller",
                9,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of("api.example.com", "other.example.com"), Set.of(443), false),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(4)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(32L * 1024 * 1024, 16L * 1024, 1, 8));

        PermissionProfile effective = ThirdPartyPermissionPolicy.brokerPermission(networked, Optional.of(caller));

        assertEquals(Set.of("api.example.com"), effective.network().hosts());
        assertEquals(Set.of(443), effective.network().ports());
        assertTrue(effective.network().tlsOnly());
        assertEquals(Duration.ofSeconds(4), effective.processes().maxRunTime());
        assertEquals(16L * 1024, effective.resources().outputBytes());
    }

    @Test
    void staging与installed拒绝符号链接目录() throws Exception {
        Path outside = temporaryDirectory.resolve("outside-bundle");
        Files.createDirectories(outside);
        String stagingId = "b".repeat(64);
        Path stagedLink = directories.staging(stagingId);
        Path installedLink = directories.installed("demo.link");
        try {
            Files.createSymbolicLink(stagedLink, outside);
            Files.createSymbolicLink(installedLink, outside);
            assertThrows(SecurityException.class, () -> archives.verifyStaged(stagingId));
            assertThrows(SecurityException.class, () -> archives.verifyInstalled(installedLink, stagingId));
        } catch (UnsupportedOperationException ignored) {
            // 不支持符号链接的平台仍由其 ACL 与普通目录检查覆盖。
        }
    }

    @Test
    void path与受管目录只接受规范直接子项() throws Exception {
        assertEquals("bin/worker", BundlePathNames.requireRelative("bin/worker", "path"));
        assertThrows(IllegalArgumentException.class, () -> BundlePathNames.requireRelative("a/../b", "path"));
        assertThrows(IllegalArgumentException.class, () -> BundlePathNames.requireRelative("a\\b", "path"));
        assertThrows(IllegalArgumentException.class, () -> BundlePathNames.requireRelative("/tmp/a", "path"));
        assertThrows(IllegalArgumentException.class, () -> BundlePathNames.requireRelative(".", "path"));

        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside);
        assertThrows(SecurityException.class, () -> SafeBundleFileTree.delete(directories.stagingRoot(), outside));
        SafeBundleFileTree.delete(
                directories.stagingRoot(), directories.stagingRoot().resolve("absent"));
        assertThrows(IllegalArgumentException.class, () -> directories.installed("bad/name"));
    }

    private static ThirdPartyBundleManifest withNetworkPermission(
            ThirdPartyBundleManifest source, ThirdPartyBundleManifest.PermissionRequest permission) {
        return new ThirdPartyBundleManifest(
                source.formatVersion(),
                source.id(),
                source.displayName(),
                source.version(),
                source.signingKeyId(),
                source.entryPoint(),
                permission,
                source.contributions(),
                source.schemas(),
                source.files());
    }

    private ArchiveHarness stagedHarness(String name) throws Exception {
        ArchiveHarness harness = archiveHarness(name);
        VerifiedThirdPartyBundle staged = harness.archives()
                .stage(ThirdPartyBundleTestFixtures.attachment(
                        ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys)));
        return new ArchiveHarness(harness.archives(), harness.directories(), staged.manifestDigest(), staged.root());
    }

    private ArchiveHarness archiveHarness(String name) {
        ThirdPartyBundleDirectories isolatedDirectories = new ThirdPartyBundleDirectories(
                temporaryDirectory.resolve(name).resolve("data-v5"),
                Clock.fixed(Instant.parse("2026-09-01T03:00:00Z"), ZoneOffset.UTC));
        ThirdPartyBundleArchive isolatedArchives =
                new ThirdPartyBundleArchive(json, ThirdPartyBundleTestFixtures.trustedKeys(keys), isolatedDirectories);
        return new ArchiveHarness(isolatedArchives, isolatedDirectories, "", isolatedDirectories.stagingRoot());
    }

    private static ThirdPartyBundleManifest withPermission(
            ThirdPartyBundleManifest source, ThirdPartyBundleManifest.PermissionRequest permission) {
        return new ThirdPartyBundleManifest(
                source.formatVersion(),
                source.id(),
                source.displayName(),
                source.version(),
                source.signingKeyId(),
                source.entryPoint(),
                permission,
                source.contributions(),
                source.schemas(),
                source.files());
    }

    private Path archiveWithEntries(String name, int count, boolean directory, int contentSize) throws Exception {
        Path archive = temporaryDirectory.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int index = 0; index < count; index++) {
                String entryName = directory ? "directory/" : index == 0 ? "manifest.json" : "file-" + index;
                zip.putNextEntry(new ZipEntry(entryName));
                if (!directory) {
                    zip.write(new byte[contentSize]);
                }
                zip.closeEntry();
            }
        }
        return archive;
    }

    private record ArchiveHarness(
            ThirdPartyBundleArchive archives,
            ThirdPartyBundleDirectories directories,
            String stagingId,
            Path stagedRoot) {}
}
