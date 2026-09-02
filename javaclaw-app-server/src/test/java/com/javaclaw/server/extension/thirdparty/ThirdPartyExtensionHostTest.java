package com.javaclaw.server.extension.thirdparty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ExtensionAccessDeniedException;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionTrustKeyRecord;
import com.javaclaw.server.persistence.ExtensionTrustKeyRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyExtensionHostTest {
    private static final Instant NOW = Instant.parse("2026-09-01T02:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private MutableClock clock;
    private CanonicalJson json;
    private H2Database database;
    private CoreCommandService core;
    private AttachmentService attachments;
    private KeyPair keys;
    private FakeSandbox sandbox;

    @BeforeEach
    void initialize() {
        clock = new MutableClock(NOW);
        json = new CanonicalJson();
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        attachments = new AttachmentService(database, json, clock);
        keys = ThirdPartyBundleTestFixtures.keyPair();
        importTrustKey();
        sandbox = new FakeSandbox(json);
    }

    @Test
    void signedBundle完整执行审阅安装启用调用禁用与Trash移除() throws Exception {
        Path archive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        Aggregate aggregate = aggregate();
        try (ThirdPartyExtensionHost host = host()) {
            BundleRpcContracts.StageResult staged = stage(host, archive);
            BundleRpcContracts.StageResult restaged = stage(host, archive);
            BundleRpcContracts.Bundle installed = install(host, staged);
            BundleRpcContracts.Bundle retried = install(host, staged);

            assertEquals(staged, restaged);
            assertEquals(ThirdPartyBundleTestFixtures.EXTENSION_ID, staged.extensionId());
            assertEquals("worker", staged.permissions().executableName());
            assertTrue(staged.permissions().workspaceRead());
            assertFalse(staged.permissions().workspaceWrite());
            assertEquals("INSTALLED", installed.state());
            assertEquals(installed, retried);
            assertTrue(host.tools().isEmpty());
            assertTrue(host.installed(ThirdPartyBundleTestFixtures.EXTENSION_ID));

            BundleRpcContracts.Bundle enabled = host.enable(installed.id(), installed.revision());
            ExtensionRpcContracts.CallPayload query = call(aggregate, "status", new CanonicalPayload("{}"));
            ExtensionRpcContracts.CallPayload command = call(aggregate, "put", json.parse("{\"value\":\"saved\"}"));
            var queryResult = host.query(query);
            var commandResult = host.command(command, "command-key", 0);
            ToolDescriptor tool = host.tools().getFirst();
            ToolCallRequest toolRequest = new ToolCallRequest(
                    aggregate.turn().id(), "call-1", tool.identity(), new CanonicalPayload("{}"), "effect-key", 1);
            var toolResult = host.executeTool(
                    toolRequest, tool, callerPermission(aggregate.workspace()), new CancellationSource());

            assertEquals("ENABLED", enabled.state());
            assertEquals("ok", json.textField(queryResult.payload(), "status").orElseThrow());
            assertEquals(1, commandResult.revision());
            assertEquals("ok", json.textField(toolResult.payload(), "status").orElseThrow());
            assertEquals(
                    "demo/schema@1",
                    host.schema(installed.id(), "demo/schema@1").schemaId());
            assertEquals(
                    "demo.main",
                    host.views(Optional.of(installed.id())).getFirst().viewId());
            assertEquals(1, host.views(Optional.empty()).size());
            assertTrue(sandbox.inputs.stream().anyMatch(input -> input.contains("\"kind\":\"HEALTH\"")));
            assertTrue(sandbox.lastPermission.files().readRoots().stream()
                    .anyMatch(root -> root.equals(aggregate.workspace().root())));

            BundleRpcContracts.Bundle disabled = host.disable(installed.id(), installed.revision());
            assertEquals("DISABLED", disabled.state());
            assertEquals(disabled, host.disable(installed.id(), installed.revision()));
            assertTrue(host.tools().isEmpty());
            assertThrows(ExtensionAccessDeniedException.class, () -> host.query(query));

            BundleRpcContracts.TrashEntry removed = host.uninstall(installed.id(), installed.revision());
            BundleRpcContracts.TrashEntry retriedRemoval = host.uninstall(installed.id(), installed.revision());
            assertEquals(removed, retriedRemoval);
            assertFalse(host.installed(installed.id()));
            assertTrue(Files.isDirectory(
                    database.dataRoot().resolve("extensions/Trash").resolve(removed.trashId())));
        }
    }

    @Test
    void invocation连续失败执行退避并在第三次隔离() throws Exception {
        Path archive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        Aggregate aggregate = aggregate();
        try (ThirdPartyExtensionHost host = host()) {
            var staged = stage(host, archive);
            var installed = install(host, staged);
            host.enable(installed.id(), 1);
            sandbox.mode = FakeSandbox.Mode.EXIT_NON_HEALTH;
            ExtensionRpcContracts.CallPayload query = call(aggregate, "status", new CanonicalPayload("{}"));

            assertThrows(IllegalStateException.class, () -> host.query(query));
            assertThrows(ExtensionAccessDeniedException.class, () -> host.query(query));
            clock.advance(Duration.ofSeconds(3));
            assertThrows(IllegalStateException.class, () -> host.query(query));
            clock.advance(Duration.ofSeconds(5));
            assertThrows(IllegalStateException.class, () -> host.query(query));

            assertEquals("QUARANTINED", host.list().getFirst().state());
            assertTrue(host.tools().isEmpty());
            sandbox.mode = FakeSandbox.Mode.SUCCESS;
            assertEquals("ENABLED", host.enable(installed.id(), 1).state());
        }
    }

    @Test
    void health失败立即隔离且显式重试可恢复() throws Exception {
        Path archive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        try (ThirdPartyExtensionHost host = host()) {
            var staged = stage(host, archive);
            var installed = install(host, staged);
            sandbox.mode = FakeSandbox.Mode.EXIT_ALL;

            assertThrows(IllegalStateException.class, () -> host.enable(installed.id(), 1));
            assertEquals(
                    ExtensionState.QUARANTINED.name(), host.list().getFirst().state());

            sandbox.mode = FakeSandbox.Mode.SUCCESS;
            assertEquals("ENABLED", host.enable(installed.id(), 1).state());
        }
    }

    @Test
    void 原子升级保留启停语义并将旧Revision移入可审计Trash() throws Exception {
        Path initialArchive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        Path disabledUpgrade = ThirdPartyBundleTestFixtures.archiveVersion(temporaryDirectory, json, keys, "5.0.1");
        Path enabledUpgrade = ThirdPartyBundleTestFixtures.archiveVersion(temporaryDirectory, json, keys, "5.0.2");
        Path staleUpgrade = ThirdPartyBundleTestFixtures.archiveVersion(temporaryDirectory, json, keys, "5.0.3");
        try (ThirdPartyExtensionHost host = host()) {
            BundleRpcContracts.Bundle initial = install(host, stage(host, initialArchive));

            BundleRpcContracts.StageResult disabledStage = stage(host, disabledUpgrade);
            assertThrows(
                    PersistenceException.class,
                    () -> host.upgrade(
                            new BundleRpcContracts.CommitPayload(
                                    disabledStage.stagingId(), disabledStage.manifestDigest()),
                            0));
            BundleRpcContracts.Bundle disabled = host.upgrade(
                    new BundleRpcContracts.CommitPayload(disabledStage.stagingId(), disabledStage.manifestDigest()),
                    initial.revision());

            assertEquals(2, disabled.revision());
            assertEquals("5.0.1", disabled.version());
            assertEquals("DISABLED", disabled.state());
            assertTrue(host.tools().isEmpty());

            BundleRpcContracts.Bundle enabled = host.enable(disabled.id(), disabled.revision());
            BundleRpcContracts.StageResult enabledStage = stage(host, enabledUpgrade);
            BundleRpcContracts.Bundle upgraded = host.upgrade(
                    new BundleRpcContracts.CommitPayload(enabledStage.stagingId(), enabledStage.manifestDigest()),
                    enabled.revision());

            assertEquals(3, upgraded.revision());
            assertEquals("5.0.2", upgraded.version());
            assertEquals("ENABLED", upgraded.state());
            assertEquals(3, host.tools().getFirst().identity().revision());
            assertEquals(2, host.listTrash().size());
            assertEquals(
                    Set.of("5.0.0", "5.0.1"),
                    host.listTrash().stream()
                            .map(BundleRpcContracts.TrashEntry::version)
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(host.listTrash().stream()
                    .allMatch(entry -> entry.state() == BundleRpcContracts.TrashState.TRASHED));
            BundleRpcContracts.StageResult staleStage = stage(host, staleUpgrade);
            assertThrows(
                    PersistenceException.class,
                    () -> host.upgrade(
                            new BundleRpcContracts.CommitPayload(staleStage.stagingId(), staleStage.manifestDigest()),
                            enabled.revision()));
        }
    }

    @Test
    void 升级健康失败回滚到原Revision且不会发布候选工具() throws Exception {
        Path initialArchive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        Path replacementArchive = ThirdPartyBundleTestFixtures.archiveVersion(temporaryDirectory, json, keys, "5.0.1");
        try (ThirdPartyExtensionHost host = host()) {
            BundleRpcContracts.Bundle initial = install(host, stage(host, initialArchive));
            BundleRpcContracts.Bundle enabled = host.enable(initial.id(), initial.revision());
            BundleRpcContracts.StageResult replacement = stage(host, replacementArchive);
            sandbox.mode = FakeSandbox.Mode.EXIT_ALL;

            assertThrows(
                    IllegalStateException.class,
                    () -> host.upgrade(
                            new BundleRpcContracts.CommitPayload(replacement.stagingId(), replacement.manifestDigest()),
                            enabled.revision()));

            BundleRpcContracts.Bundle recovered = host.readBundle(initial.id());
            assertEquals(initial.revision(), recovered.revision());
            assertEquals(initial.version(), recovered.version());
            assertEquals("ENABLED", recovered.state());
            assertEquals(initial.revision(), host.tools().getFirst().identity().revision());
            assertTrue(host.listTrash().isEmpty());
        }
    }

    @Test
    void 主动健康探测清除旧失败并在连续失败后隔离() throws Exception {
        Path archive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        try (ThirdPartyExtensionHost host = host()) {
            BundleRpcContracts.Bundle installed = install(host, stage(host, archive));
            BundleRpcContracts.Bundle enabled = host.enable(installed.id(), installed.revision());

            assertEquals(
                    0, host.probe(enabled.id(), enabled.revision()).health().failureCount());
            assertEquals(1, host.tools().size());
            sandbox.mode = FakeSandbox.Mode.EXIT_ALL;
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThrows(IllegalStateException.class, () -> host.probe(enabled.id(), enabled.revision()));
            }

            BundleRpcContracts.Bundle quarantined = host.readBundle(enabled.id());
            assertEquals("QUARANTINED", quarantined.state());
            assertEquals(3, quarantined.health().failureCount());
            assertTrue(host.tools().isEmpty());
        }
    }

    @Test
    void bundle管理命令拒绝错误摘要Revision和不存在资源() throws Exception {
        Path initialArchive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        Path replacementArchive = ThirdPartyBundleTestFixtures.archiveVersion(temporaryDirectory, json, keys, "5.0.1");
        try (ThirdPartyExtensionHost host = host()) {
            AttachmentMetadata attachment = storeArchive(initialArchive);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new BundleRpcContracts.AttachmentPointer(attachment.digest(), "f".repeat(64)));

            BundleRpcContracts.StageResult staged =
                    host.stage(new BundleRpcContracts.AttachmentPointer(attachment.digest(), attachment.digest()));
            assertThrows(
                    PersistenceException.class,
                    () -> host.install(
                            new BundleRpcContracts.CommitPayload(staged.stagingId(), staged.manifestDigest()), 1));
            BundleRpcContracts.StageResult replacement = stage(host, replacementArchive);
            assertThrows(
                    PersistenceException.class,
                    () -> host.upgrade(
                            new BundleRpcContracts.CommitPayload(replacement.stagingId(), replacement.manifestDigest()),
                            1));

            BundleRpcContracts.Bundle installed = install(host, staged);
            BundleRpcContracts.Bundle enabled = host.enable(installed.id(), installed.revision());
            assertEquals(enabled, host.enable(enabled.id(), enabled.revision()));
            assertThrows(PersistenceException.class, () -> host.enable(enabled.id(), enabled.revision() + 1));
            assertThrows(PersistenceException.class, () -> host.readBundle("missing.extension"));
        }
    }

    @Test
    void trash终态拒绝恢复清除并保持相同命令幂等() throws Exception {
        Path archive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        try (ThirdPartyExtensionHost host = host()) {
            BundleRpcContracts.Bundle installed = install(host, stage(host, archive));
            BundleRpcContracts.TrashEntry removed = host.uninstall(installed.id(), installed.revision());

            assertThrows(PersistenceException.class, () -> host.readTrash("missing-trash"));
            assertThrows(
                    PersistenceException.class, () -> host.restoreTrash(removed.trashId(), removed.revision() + 1));
            assertThrows(PersistenceException.class, () -> host.purgeTrash(removed.trashId(), removed.revision() + 1));

            BundleRpcContracts.Bundle restored = host.restoreTrash(removed.trashId(), removed.revision());
            assertThrows(PersistenceException.class, () -> host.restoreTrash(removed.trashId(), removed.revision()));
            assertThrows(PersistenceException.class, () -> host.purgeTrash(removed.trashId(), removed.revision()));

            BundleRpcContracts.TrashEntry removedAgain = host.uninstall(restored.id(), restored.revision());
            BundleRpcContracts.TrashEntry purged = host.purgeTrash(removedAgain.trashId(), removedAgain.revision());
            assertEquals(purged, host.purgeTrash(removedAgain.trashId(), removedAgain.revision()));
            assertThrows(
                    PersistenceException.class,
                    () -> host.restoreTrash(removedAgain.trashId(), removedAgain.revision()));
        }
    }

    @Test
    void host拒绝错误操作上下文冻结工具与关闭后调用() throws Exception {
        Path archive = ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys);
        Aggregate first = aggregate();
        Aggregate second = aggregate();
        ThirdPartyExtensionHost host = host();
        var staged = stage(host, archive);
        var installed = install(host, staged);
        host.enable(installed.id(), 1);

        assertThrows(
                IllegalArgumentException.class, () -> host.query(call(first, "missing", new CanonicalPayload("{}"))));
        ExtensionRpcContracts.CallPayload wrongThread = new ExtensionRpcContracts.CallPayload(
                installed.id(),
                first.workspace().id(),
                Optional.of(second.thread().id()),
                Optional.empty(),
                "status",
                new CanonicalPayload("{}"));
        assertThrows(IllegalArgumentException.class, () -> host.query(wrongThread));
        ToolDescriptor tool = host.tools().getFirst();
        ToolDescriptor changed = new ToolDescriptor(
                tool.identity(), "changed", tool.inputSchema(), tool.outputSchema(), tool.risk(), tool.tags());
        ToolCallRequest request = new ToolCallRequest(
                first.turn().id(), "call", tool.identity(), new CanonicalPayload("{}"), "effect", 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> host.executeTool(
                        request, changed, callerPermission(first.workspace()), new CancellationSource()));
        assertThrows(IllegalArgumentException.class, () -> host.schema(installed.id(), "missing"));

        host.close();
        host.close();
        assertThrows(IllegalStateException.class, host::list);
    }

    private ThirdPartyExtensionHost host() {
        ThirdPartyBundleDirectories directories = new ThirdPartyBundleDirectories(database.dataRoot(), clock);
        return ThirdPartyExtensionFactory.start(new ThirdPartyExtensionFactory.Bootstrap(
                database,
                json,
                clock,
                new ThirdPartyExtensionHost.ExecutionPorts(sandbox, (request, permission, cancellation) -> {
                    throw new AssertionError("this fixture does not request networking");
                }),
                core,
                List.of(),
                directories,
                attachments));
    }

    private void importTrustKey() {
        byte[] encoded = keys.getPublic().getEncoded();
        AttachmentRpcContracts.BeginPayload payload =
                attachmentPayload(BundleRpcContracts.PUBLIC_KEY_MEDIA_TYPE, encoded);
        var attachment = attachments.store(
                AttachmentScope.global(),
                identity("attachment/internal/store", "fixture-trust-key", payload),
                payload.mediaType(),
                encoded);
        new ExtensionTrustKeyRepository(database, clock)
                .importKey(ExtensionTrustKeyRecord.active(
                        ThirdPartyBundleTestFixtures.KEY_ID,
                        ThirdPartyBundleTestFixtures.digest(encoded),
                        attachment.digest(),
                        encoded,
                        NOW));
    }

    private BundleRpcContracts.StageResult stage(ThirdPartyExtensionHost host, Path archive) throws Exception {
        AttachmentMetadata attachment = storeArchive(archive);
        return host.stage(new BundleRpcContracts.AttachmentPointer(attachment.digest(), attachment.digest()));
    }

    private AttachmentMetadata storeArchive(Path archive) throws Exception {
        byte[] content = Files.readAllBytes(archive);
        AttachmentRpcContracts.BeginPayload payload = attachmentPayload(BundleRpcContracts.BUNDLE_MEDIA_TYPE, content);
        return attachments.store(
                AttachmentScope.global(),
                identity("attachment/internal/store", UUID.randomUUID().toString(), payload),
                payload.mediaType(),
                content);
    }

    private static AttachmentRpcContracts.BeginPayload attachmentPayload(String mediaType, byte[] content) {
        return new AttachmentRpcContracts.BeginPayload(
                AttachmentScope.global(), mediaType, ThirdPartyBundleTestFixtures.digest(content), content.length);
    }

    private static BundleRpcContracts.Bundle install(
            ThirdPartyExtensionHost host, BundleRpcContracts.StageResult staged) {
        return host.install(new BundleRpcContracts.CommitPayload(staged.stagingId(), staged.manifestDigest()), 0);
    }

    private Aggregate aggregate() throws Exception {
        Path root = Files.createTempDirectory(temporaryDirectory, "workspace-")
                .toAbsolutePath()
                .normalize();
        CoreRpcContracts.WorkspaceCreatePayload workspacePayload =
                new CoreRpcContracts.WorkspaceCreatePayload("Workspace", root);
        Workspace workspace = core.createWorkspace(
                identity("workspace/create", UUID.randomUUID().toString(), workspacePayload),
                workspacePayload.name(),
                workspacePayload.root());
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, "Thread");
        ConversationThread thread = core.createThread(
                identity("thread/create", UUID.randomUUID().toString(), threadPayload),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                threadPayload.title());
        CorePayloads.Message input = new CorePayloads.Message(MessageRole.USER, "hello", List.of(), Optional.empty());
        TurnBudget budget = new TurnBudget(1000, 1000, 4, 0, Duration.ofMinutes(1));
        CoreRpcContracts.TurnStartPayload turnPayload =
                com.javaclaw.server.TurnContractFixtures.payload(thread.id(), input.text());
        AgentTurn turn = core.startTurn(
                identity("turn/start", UUID.randomUUID().toString(), turnPayload),
                com.javaclaw.server.TurnContractFixtures.request(thread.id(), budget, input));
        return new Aggregate(workspace, thread, turn);
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.encode(payload)), json);
    }

    private static ExtensionRpcContracts.CallPayload call(
            Aggregate aggregate, String operation, CanonicalPayload payload) {
        return new ExtensionRpcContracts.CallPayload(
                ThirdPartyBundleTestFixtures.EXTENSION_ID,
                aggregate.workspace().id(),
                Optional.of(aggregate.thread().id()),
                Optional.of(aggregate.turn().id()),
                operation,
                payload);
    }

    private static PermissionProfile callerPermission(Workspace workspace) {
        return new PermissionProfile(
                "caller",
                1,
                new FilePermission(List.of(workspace.root()), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of("worker"), false, Duration.ofSeconds(10)),
                new ToolPermission(Set.of("demo_lookup"), ToolRisk.READ_ONLY, ApprovalRequirement.RISKY),
                new ResourceLimits(64L * 1024 * 1024, 64L * 1024, 1, 16));
    }

    private record Aggregate(Workspace workspace, ConversationThread thread, AgentTurn turn) {}

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FakeSandbox implements SandboxExecutor {
        private final CanonicalJson json;
        private final List<String> inputs = new ArrayList<>();
        private Mode mode = Mode.SUCCESS;
        private PermissionProfile lastPermission;

        private FakeSandbox(CanonicalJson json) {
            this.json = json;
        }

        @Override
        public SandboxResult execute(
                SandboxCommand command, PermissionProfile permission, com.javaclaw.api.CancellationToken cancellation) {
            String input = new String(command.standardInput(), StandardCharsets.UTF_8);
            inputs.add(input);
            lastPermission = permission;
            boolean health = input.contains("\"kind\":\"HEALTH\"");
            if (mode == Mode.EXIT_ALL || mode == Mode.EXIT_NON_HEALTH && !health) {
                return result(7, new byte[0], false, false);
            }
            CanonicalPayload response = json.parse("""
                    {"actions":[],"error":null,"ok":true,"payload":{"status":"ok"},"revision":1}
                    """);
            return result(0, response.json().getBytes(StandardCharsets.UTF_8), false, false);
        }

        @Override
        public SandboxSession open(
                SandboxCommand command, PermissionProfile permission, com.javaclaw.api.CancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }

        private static SandboxResult result(int exit, byte[] output, boolean timedOut, boolean cancelled) {
            return new SandboxResult(exit, output, new byte[0], timedOut, cancelled, Duration.ofMillis(1));
        }

        private enum Mode {
            SUCCESS,
            EXIT_NON_HEALTH,
            EXIT_ALL
        }
    }
}
