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
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionTrustKeyRecord;
import com.javaclaw.server.persistence.ExtensionTrustKeyRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ThirdPartyExtensionRecord;
import com.javaclaw.server.persistence.ThirdPartyExtensionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyBundleRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-09-01T06:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private Clock clock;
    private CanonicalJson json;
    private H2Database database;
    private AttachmentService attachments;
    private KeyPair keys;
    private HealthySandbox sandbox;

    @BeforeEach
    void initialize() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        json = new CanonicalJson();
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        attachments = new AttachmentService(database, json, clock);
        keys = ThirdPartyBundleTestFixtures.keyPair();
        sandbox = new HealthySandbox(json);
        importTrustKey();
    }

    @Test
    void enabledBundle重启后重新验签健康检查并恢复工具目录() throws Exception {
        BundleRpcContracts.Bundle enabled;
        try (ThirdPartyExtensionHost first = host(List.of())) {
            BundleRpcContracts.Bundle installed = install(first);
            enabled = first.enable(installed.id(), installed.revision());
        }

        try (ThirdPartyExtensionHost restarted = host(List.of())) {
            assertEquals(enabled, restarted.readBundle(enabled.id()));
            assertEquals(1, restarted.tools().size());
            assertTrue(sandbox.healthChecks >= 2);
        }
    }

    @Test
    void starting状态重启后标记隔离而不发布候选工具() throws Exception {
        ThirdPartyExtensionRecord installed;
        try (ThirdPartyExtensionHost first = host(List.of())) {
            BundleRpcContracts.Bundle bundle = install(first);
            installed = repository().find(new ExtensionId(bundle.id())).orElseThrow();
        }
        repository()
                .transition(
                        installed.descriptor().id(),
                        installed.descriptor().revision(),
                        Set.of(ExtensionState.INSTALLED),
                        ExtensionState.STARTING);

        try (ThirdPartyExtensionHost restarted = host(List.of())) {
            BundleRpcContracts.Bundle quarantined =
                    restarted.readBundle(installed.descriptor().id().value());
            assertEquals("QUARANTINED", quarantined.state());
            assertEquals(1, quarantined.health().failureCount());
            assertTrue(restarted.tools().isEmpty());
        }
    }

    @Test
    void installed内容损坏时重启恢复FailClosed并记录隔离() throws Exception {
        ThirdPartyExtensionRecord installed;
        try (ThirdPartyExtensionHost first = host(List.of())) {
            BundleRpcContracts.Bundle bundle = install(first);
            installed = repository().find(new ExtensionId(bundle.id())).orElseThrow();
        }
        ThirdPartyBundleDirectories directories = directories();
        Files.writeString(
                directories.installed(installed.installDirectory()).resolve("bin/worker"),
                "tampered",
                StandardCharsets.UTF_8);

        try (ThirdPartyExtensionHost restarted = host(List.of())) {
            BundleRpcContracts.Bundle quarantined =
                    restarted.readBundle(installed.descriptor().id().value());
            assertEquals("QUARANTINED", quarantined.state());
            assertEquals(1, quarantined.health().failureCount());
            assertTrue(restarted.tools().isEmpty());
        }
    }

    @Test
    void removing状态重启后完成移动并保留可恢复Trash() throws Exception {
        ThirdPartyExtensionRecord removing = beginRemoval(false);

        try (ThirdPartyExtensionHost restarted = host(List.of())) {
            assertFalse(restarted.installed(removing.descriptor().id().value()));
            assertEquals(1, restarted.listTrash().size());
            assertEquals(
                    BundleRpcContracts.TrashState.TRASHED,
                    restarted.listTrash().getFirst().state());
        }
    }

    @Test
    void removing目录丢失时重启记录隔离而不伪造Trash完成() throws Exception {
        ThirdPartyExtensionRecord removing = beginRemoval(true);

        try (ThirdPartyExtensionHost restarted = host(List.of())) {
            BundleRpcContracts.Bundle quarantined =
                    restarted.readBundle(removing.descriptor().id().value());
            assertEquals("QUARANTINED", quarantined.state());
            assertEquals(1, quarantined.health().failureCount());
            assertTrue(restarted.listTrash().isEmpty());
        }
    }

    @Test
    void trash恢复发现工具名已保留时回滚文件且不创建活动记录() throws Exception {
        BundleRpcContracts.TrashEntry removed;
        try (ThirdPartyExtensionHost first = host(List.of())) {
            BundleRpcContracts.Bundle installed = install(first);
            removed = first.uninstall(installed.id(), installed.revision());
        }

        try (ThirdPartyExtensionHost restricted = host(List.of(reservedTool()))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> restricted.restoreTrash(removed.trashId(), removed.revision()));
            assertFalse(restricted.installed(removed.extensionId()));
            assertEquals(
                    BundleRpcContracts.TrashState.TRASHED,
                    restricted.readTrash(removed.trashId()).state());
            assertTrue(Files.isDirectory(
                    database.dataRoot().resolve("extensions/Trash").resolve(removed.trashId())));
        }
    }

    private ThirdPartyExtensionRecord beginRemoval(boolean deleteInstalledDirectory) throws Exception {
        BundleRpcContracts.Bundle installed;
        try (ThirdPartyExtensionHost first = host(List.of())) {
            installed = install(first);
        }
        ThirdPartyExtensionRepository repository = repository();
        ThirdPartyExtensionRecord current =
                repository.find(new ExtensionId(installed.id())).orElseThrow();
        ThirdPartyBundleDirectories directories = directories();
        String trashId = directories.trashName(installed.id(), installed.revision(), current.manifestDigest());
        ThirdPartyExtensionRecord removing = repository.beginRemoval(
                current.descriptor().id(), current.descriptor().revision(), trashId);
        if (deleteInstalledDirectory) {
            directories.purgeInstalled(current.installDirectory());
        }
        return removing;
    }

    private BundleRpcContracts.Bundle install(ThirdPartyExtensionHost host) throws Exception {
        Path archive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        byte[] content = Files.readAllBytes(archive);
        AttachmentRpcContracts.BeginPayload payload = new AttachmentRpcContracts.BeginPayload(
                AttachmentScope.global(),
                BundleRpcContracts.BUNDLE_MEDIA_TYPE,
                ThirdPartyBundleTestFixtures.digest(content),
                content.length);
        var attachment = attachments.store(
                AttachmentScope.global(),
                identity("attachment/internal/store", UUID.randomUUID().toString(), payload),
                payload.mediaType(),
                content);
        BundleRpcContracts.StageResult staged =
                host.stage(new BundleRpcContracts.AttachmentPointer(attachment.digest(), attachment.digest()));
        return host.install(new BundleRpcContracts.CommitPayload(staged.stagingId(), staged.manifestDigest()), 0);
    }

    private ThirdPartyExtensionHost host(List<ToolDescriptor> reservedTools) {
        return ThirdPartyExtensionFactory.start(new ThirdPartyExtensionFactory.Bootstrap(
                database,
                json,
                clock,
                new ThirdPartyExtensionHost.ExecutionPorts(sandbox, (request, permission, cancellation) -> {
                    throw new AssertionError("恢复测试不允许访问网络");
                }),
                new CoreCommandService(database, json, clock),
                reservedTools,
                directories(),
                attachments));
    }

    private ThirdPartyExtensionRepository repository() {
        return new ThirdPartyExtensionRepository(database, json, clock);
    }

    private ThirdPartyBundleDirectories directories() {
        return new ThirdPartyBundleDirectories(database.dataRoot(), clock);
    }

    private ToolDescriptor reservedTool() {
        CanonicalPayload schema = json.parse("{\"type\":\"object\"}");
        return new ToolDescriptor(
                new ToolIdentity("core", "demo_lookup", 1),
                "平台保留工具",
                schema,
                schema,
                ToolRisk.READ_ONLY,
                Set.of("reserved"));
    }

    private void importTrustKey() {
        byte[] encoded = keys.getPublic().getEncoded();
        String digest = ThirdPartyBundleTestFixtures.digest(encoded);
        AttachmentRpcContracts.BeginPayload payload = new AttachmentRpcContracts.BeginPayload(
                AttachmentScope.global(), BundleRpcContracts.PUBLIC_KEY_MEDIA_TYPE, digest, encoded.length);
        var attachment = attachments.store(
                AttachmentScope.global(),
                identity("attachment/internal/store", "recovery-trust-key", payload),
                payload.mediaType(),
                encoded);
        new ExtensionTrustKeyRepository(database, clock)
                .importKey(ExtensionTrustKeyRecord.active(
                        ThirdPartyBundleTestFixtures.KEY_ID, digest, attachment.digest(), encoded, NOW));
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.encode(payload)), json);
    }

    private static final class HealthySandbox implements SandboxExecutor {
        private final CanonicalJson json;
        private int healthChecks;

        private HealthySandbox(CanonicalJson json) {
            this.json = json;
        }

        @Override
        public SandboxResult execute(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            String input = new String(command.standardInput(), StandardCharsets.UTF_8);
            if (input.contains("\"kind\":\"HEALTH\"")) {
                healthChecks++;
            }
            CanonicalPayload response =
                    json.parse("{\"actions\":[],\"error\":null,\"ok\":true,\"payload\":{},\"revision\":1}");
            return new SandboxResult(
                    0,
                    response.json().getBytes(StandardCharsets.UTF_8),
                    new byte[0],
                    false,
                    false,
                    Duration.ofMillis(1));
        }

        @Override
        public SandboxSession open(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }
    }
}
