package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAccessDeniedException;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.protocol.AttachmentRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceConfigurationBranchesTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private Clock clock;

    @BeforeEach
    void initializeDataV6() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Test
    void permissionProfile克隆幂等并拒绝错误命令与目标() {
        PermissionProfileService permissions = permissionService();
        PermissionProfileRpcContracts.ClonePayload payload = new PermissionProfileRpcContracts.ClonePayload(
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1), "developer");
        CommandIdentity clone = identity("permissionProfile/clone", "clone", 0, payload);

        PermissionProfile created = permissions.cloneProfile(clone, payload.source(), payload.newId());
        assertEquals(created, permissions.cloneProfile(clone, payload.source(), payload.newId()));

        PermissionProfileRpcContracts.ClonePayload changed =
                new PermissionProfileRpcContracts.ClonePayload(payload.source(), "operator");
        assertThrows(
                PersistenceException.class,
                () -> permissions.cloneProfile(
                        identity("permissionProfile/clone", "clone", 0, changed), changed.source(), changed.newId()));
        assertThrows(
                PersistenceException.class,
                () -> permissions.cloneProfile(
                        identity("permissionProfile/update", "wrong-method", 0, payload),
                        payload.source(),
                        "wrong-method"));
        assertThrows(
                PersistenceException.class,
                () -> permissions.cloneProfile(
                        identity("permissionProfile/clone", "wrong-revision", 1, payload),
                        payload.source(),
                        "wrong-revision"));
        assertThrows(
                PersistenceException.class,
                () -> permissions.cloneProfile(cloneIdentity("standard", "standard"), payload.source(), "standard"));
        assertThrows(
                PersistenceException.class,
                () -> permissions.cloneProfile(
                        cloneIdentity("missing-source", "missing"), new PermissionProfileRef("missing", 1), "missing"));
    }

    @Test
    void permissionProfile更新拒绝错误方法版本和通配授权() {
        PermissionProfileService permissions = permissionService();
        PermissionProfile base = clone(permissions, "runtime");
        PermissionProfile next = copy(base, 2, base.files(), base.processes(), base.tools());
        PermissionProfileRpcContracts.UpdatePayload payload = new PermissionProfileRpcContracts.UpdatePayload(next);

        assertThrows(
                PersistenceException.class,
                () -> permissions.update(identity("permissionProfile/clone", "wrong-update", 1, payload), next));
        assertThrows(
                PersistenceException.class,
                () -> permissions.update(
                        identity("permissionProfile/update", "bad-version", 1, payload),
                        copy(base, 3, base.files(), base.processes(), base.tools())));
        PermissionProfile wildcardProcess = copy(
                base, 2, base.files(), new ProcessPermission(Set.of("*"), false, Duration.ofSeconds(5)), base.tools());
        assertThrows(
                PersistenceException.class,
                () -> permissions.update(
                        identity(
                                "permissionProfile/update",
                                "wildcard-process",
                                1,
                                new PermissionProfileRpcContracts.UpdatePayload(wildcardProcess)),
                        wildcardProcess));
        PermissionProfile wildcardTool = copy(
                base,
                2,
                base.files(),
                base.processes(),
                new ToolPermission(Set.of("*"), ToolRisk.READ_ONLY, ApprovalRequirement.RISKY));
        assertThrows(
                PersistenceException.class,
                () -> permissions.update(
                        identity(
                                "permissionProfile/update",
                                "wildcard-tool",
                                1,
                                new PermissionProfileRpcContracts.UpdatePayload(wildcardTool)),
                        wildcardTool));
    }

    @Test
    void permissionPreview解释系统上限并将Workspace路径映射到ExecutionRoot() {
        Path workspaceRoot =
                temporaryDirectory.resolve("workspace").toAbsolutePath().normalize();
        Path executionRoot =
                temporaryDirectory.resolve("worktree").toAbsolutePath().normalize();
        Path outside =
                temporaryDirectory.resolveSibling("outside").toAbsolutePath().normalize();
        PermissionProfile broad = new PermissionProfile(
                "broad",
                1,
                new FilePermission(
                        List.of(workspaceRoot.resolve("docs"), workspaceRoot.getRoot(), outside),
                        List.of(workspaceRoot.resolve("src"), outside),
                        true,
                        true),
                new NetworkPermission(Set.of("example.invalid"), Set.of(443), false),
                new ProcessPermission(Set.of("git"), true, Duration.ofHours(2)),
                new ToolPermission(Set.of("write"), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.NONE),
                new ResourceLimits(5L * 1024 * 1024 * 1024, 300L * 1024 * 1024, 65, 1_025));
        Workspace workspace =
                new Workspace(WorkspaceId.random(), "测试", workspaceRoot, WorkspaceLifecycle.ACTIVE, 1, NOW, NOW);

        var preview = PermissionPreviewEvaluator.evaluate(broad, broad, workspace, Optional.empty(), Optional.empty());
        var mapped = PermissionPreviewEvaluator.evaluateExecution(broad, broad, workspace, executionRoot, true);
        var readOnly = PermissionPreviewEvaluator.evaluateExecution(broad, broad, workspace, executionRoot, false);

        assertEquals(4, preview.layers().getFirst().denialReasons().size());
        assertTrue(mapped.effective().files().readRoots().contains(executionRoot.resolve("docs")));
        assertTrue(mapped.effective().files().readRoots().contains(executionRoot));
        assertFalse(mapped.effective().files().readRoots().contains(outside));
        assertEquals(
                List.of(executionRoot.resolve("src")),
                mapped.effective().files().writeRoots());
        assertTrue(readOnly.effective().files().writeRoots().isEmpty());
        assertFalse(readOnly.layers().get(3).applied());
        assertFalse(readOnly.layers().get(4).applied());
    }

    @Test
    void extensionCatalog覆盖实时启停缺失revision与行身份校验() throws Exception {
        ExtensionCatalogRepository catalog = new ExtensionCatalogRepository(database, json, clock);
        ExtensionDescriptor descriptor = descriptor("builtin.optional", ExtensionTrust.BUILT_IN, true, false);
        catalog.installBuiltIn(descriptor);

        catalog.requireEnabled(descriptor.id());
        catalog.requireEnabled(descriptor.id(), 1);
        assertThrows(ExtensionAccessDeniedException.class, () -> catalog.requireEnabled(descriptor.id(), 2));
        assertThrows(
                ExtensionAccessDeniedException.class, () -> catalog.requireEnabled(new ExtensionId("com.missing"), 1));
        assertThrows(
                PersistenceException.class,
                () -> catalog.disable(
                        new CommandIdentity("wrong", "wrong", 1, "a".repeat(64)),
                        descriptor.id().value()));
        assertThrows(
                PersistenceException.class,
                () -> catalog.disable(
                        new CommandIdentity("extension/builtin/disable", "zero-revision", 0, "a".repeat(64)),
                        descriptor.id().value()));

        var disabled = catalog.disable(
                new CommandIdentity("extension/builtin/disable", "disable", 1, "b".repeat(64)),
                descriptor.id().value());
        var unchanged = catalog.disable(
                new CommandIdentity("extension/builtin/disable", "disable-again", 2, "c".repeat(64)),
                descriptor.id().value());
        assertEquals(disabled.stateRevision(), unchanged.stateRevision());
        assertThrows(ExtensionAccessDeniedException.class, () -> catalog.requireEnabled(descriptor.id()));

        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.installBuiltIn(descriptor("third.party", ExtensionTrust.THIRD_PARTY, true, false)));
        assertThrows(PersistenceException.class, () -> catalog.requireBuiltIn("com.missing"));

        updateExtension(descriptor.id(), "TRUST_LEVEL = 'THIRD_PARTY'");
        assertThrows(
                PersistenceException.class,
                () -> catalog.requireBuiltIn(descriptor.id().value()));
        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(descriptor));
        updateExtension(descriptor.id(), "TRUST_LEVEL = 'BUILT_IN', REVISION = 2");
        assertThrows(
                PersistenceException.class,
                () -> catalog.requireBuiltIn(descriptor.id().value()));
        assertThrows(PersistenceException.class, () -> catalog.installBuiltIn(descriptor));
    }

    @Test
    void extensionCatalog拒绝行与Descriptor的扩展标识漂移() throws Exception {
        ExtensionCatalogRepository catalog = new ExtensionCatalogRepository(database, json, clock);
        ExtensionDescriptor installed = descriptor("builtin.identity", ExtensionTrust.BUILT_IN, true, false);
        ExtensionDescriptor other = descriptor("builtin.other", ExtensionTrust.BUILT_IN, true, false);
        catalog.installBuiltIn(installed);
        updateExtensionDescriptor(installed.id(), json.encode(other).json());

        assertThrows(
                PersistenceException.class,
                () -> catalog.requireBuiltIn(installed.id().value()));
    }

    @Test
    void attachment幂等恢复和Blob校验拒绝改变的输入() throws Exception {
        AttachmentService attachments = new AttachmentService(database, json, clock);
        byte[] content = "attachment".getBytes(StandardCharsets.UTF_8);
        AttachmentScope scope = AttachmentScope.global();
        AttachmentRpcContracts.BeginPayload payload = new AttachmentRpcContracts.BeginPayload(
                scope, "text/plain", ManagedWorktreePolicy.sha256(content), content.length);
        CommandIdentity command = identity("attachment/internal/store", "store", 0, payload);
        var stored = attachments.store(scope, command, "text/plain", content);

        assertThrows(PersistenceException.class, () -> attachments.store(scope, command, "application/json", content));
        assertThrows(
                PersistenceException.class,
                () -> attachments.store(scope, command, "text/plain", "changed".getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                PersistenceException.class,
                () -> attachments.store(
                        scope,
                        identity("attachment/internal/store", "bad-revision", 1, payload),
                        "text/plain",
                        content));
        assertThrows(
                PersistenceException.class,
                () -> attachments.writeVerifiedBlob(stored.digest(), 0, temporaryDirectory));
        assertThrows(
                PersistenceException.class,
                () -> attachments.writeVerifiedBlob("bad", content.length, temporaryDirectory));
        assertThrows(
                PersistenceException.class,
                () -> attachments.writeVerifiedBlob(
                        stored.digest(), AttachmentRpcContracts.MAX_ATTACHMENT_BYTES + 1L, temporaryDirectory));

        Path source = Files.writeString(temporaryDirectory.resolve("source.txt"), "attachment", StandardCharsets.UTF_8);
        assertEquals(
                stored.digest(),
                Path.of(attachments.writeVerifiedBlob(stored.digest(), content.length, source))
                        .getFileName()
                        .toString()
                        .replace(".blob", ""));
        try (var connection = database.open()) {
            assertThrows(
                    PersistenceException.class,
                    () -> attachments.persistVerified(
                            connection, scope, "text/plain", stored.digest(), content.length + 1L, source.toString()));
        }
    }

    @Test
    void 持久化服务将底层Sql故障包装在明确边界() throws Exception {
        PermissionProfileService permissions = permissionService();
        ExtensionCatalogRepository catalog = new ExtensionCatalogRepository(database, json, clock);
        try (var connection = database.open();
                var statement = connection.prepareStatement("DROP TABLE CORE.PERMISSION_PROFILE CASCADE")) {
            statement.executeUpdate();
        }
        assertTrue(assertThrows(PersistenceException.class, permissions::listLatest)
                .getMessage()
                .contains("PermissionProfile"));

        try (var connection = database.open();
                var statement = connection.prepareStatement("DROP TABLE CORE.EXTENSION CASCADE")) {
            statement.executeUpdate();
        }
        assertTrue(assertThrows(PersistenceException.class, catalog::listBuiltIns)
                .getMessage()
                .contains("Extension"));
    }

    private PermissionProfileService permissionService() {
        PermissionProfileService service = new PermissionProfileService(database, json, clock);
        service.installStandardProfile();
        return service;
    }

    private PermissionProfile clone(PermissionProfileService service, String id) {
        PermissionProfileRpcContracts.ClonePayload payload = new PermissionProfileRpcContracts.ClonePayload(
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1), id);
        return service.cloneProfile(
                identity("permissionProfile/clone", "clone-" + id, 0, payload), payload.source(), id);
    }

    private CommandIdentity cloneIdentity(String key, String id) {
        PermissionProfileRpcContracts.ClonePayload payload = new PermissionProfileRpcContracts.ClonePayload(
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1), id);
        return identity("permissionProfile/clone", key, 0, payload);
    }

    private PermissionProfile copy(
            PermissionProfile source,
            long version,
            FilePermission files,
            ProcessPermission processes,
            ToolPermission tools) {
        return new PermissionProfile(
                source.id(), version, files, source.network(), processes, tools, source.resources());
    }

    private ExtensionDescriptor descriptor(String id, ExtensionTrust trust, boolean optional, boolean mcp) {
        return new ExtensionDescriptor(
                new ExtensionId(id),
                id,
                "5.0.0",
                1,
                mcp ? Set.of(ContributionKind.MCP) : Set.of(ContributionKind.QUERY),
                new ExtensionRequirements(
                        trust,
                        optional ? ExtensionAvailability.OPTIONAL : ExtensionAvailability.REQUIRED,
                        2,
                        permissionProfile(id)));
    }

    private PermissionProfile permissionProfile(String id) {
        return new PermissionProfile(
                id,
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(10)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.RISKY),
                new ResourceLimits(1024, 1024, 1, 4));
    }

    private void updateExtension(ExtensionId id, String assignment) throws Exception {
        try (var connection = database.open();
                var statement =
                        connection.prepareStatement("UPDATE CORE.EXTENSION SET " + assignment + " WHERE ID = ?")) {
            statement.setString(1, id.value());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void updateExtensionDescriptor(ExtensionId id, String descriptorPayload) throws Exception {
        try (var connection = database.open();
                var statement = connection.prepareStatement("UPDATE CORE.EXTENSION SET DESCRIPTOR = ? WHERE ID = ?")) {
            statement.setString(1, descriptorPayload);
            statement.setString(2, id.value());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }
}
