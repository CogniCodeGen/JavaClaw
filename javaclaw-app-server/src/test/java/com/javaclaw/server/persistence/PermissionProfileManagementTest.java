package com.javaclaw.server.persistence;

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
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionLayerKind;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PermissionSection;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionProfileManagementTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private PermissionProfileService service;

    @BeforeEach
    void initialize() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        service = new PermissionProfileService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        service.installStandardProfile();
    }

    @Test
    void standard只读且用户配置只能通过克隆创建() {
        PermissionProfile standard = service.require(PermissionProfileService.STANDARD_PROFILE_ID, 1);
        PermissionProfile standardV2 = copy(standard, standard.id(), 2);
        PermissionProfile direct = profile("direct", 1, Set.of("read"), ToolRisk.READ_ONLY);

        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity(
                                "permissionProfile/update",
                                "standard-update",
                                1,
                                new PermissionProfileRpcContracts.UpdatePayload(standardV2)),
                        standardV2));
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity(
                                "permissionProfile/update",
                                "direct-create",
                                0,
                                new PermissionProfileRpcContracts.UpdatePayload(direct)),
                        direct));

        PermissionProfile cloned = cloneStandard("developer", "clone-key");
        assertEquals(1, cloned.version());
        assertEquals(standard.tools(), cloned.tools());
        assertThrows(PersistenceException.class, () -> cloneStandard("developer", "second-clone"));
    }

    @Test
    void 历史差异和幂等更新保持不可变revision() {
        PermissionProfile cloned = cloneStandard("developer", "clone-key");
        PermissionProfile updated = profile("developer", 2, Set.of("read", "write"), ToolRisk.WORKSPACE_WRITE);
        PermissionProfileRpcContracts.UpdatePayload payload = new PermissionProfileRpcContracts.UpdatePayload(updated);
        CommandIdentity identity = identity("permissionProfile/update", "update-key", 1, payload);

        assertEquals(updated, service.update(identity, updated));
        assertEquals(updated, service.update(identity, updated));
        assertEquals(List.of(cloned, updated), service.history("developer"));
        PermissionProfileDiff diff = service.diff("developer", 1, 2);
        assertTrue(diff.changedSections()
                .containsAll(Set.of(
                        PermissionSection.FILE,
                        PermissionSection.NETWORK,
                        PermissionSection.PROCESS,
                        PermissionSection.TOOL,
                        PermissionSection.RESOURCE)));
        assertThrows(PersistenceException.class, () -> service.history("missing"));
    }

    @Test
    void 相同版本差异不会伪造变更分区() {
        PermissionProfile cloned = cloneStandard("unchanged", "clone-unchanged");

        PermissionProfileDiff diff = service.diff(cloned.id(), cloned.version(), cloned.version());

        assertTrue(diff.changedSections().isEmpty());
        assertEquals(cloned, diff.before());
        assertEquals(cloned, diff.after());
    }

    @Test
    void hostFullAccess表达式无法保存() {
        cloneStandard("developer", "clone-key");
        PermissionProfile safe = profile("developer", 2, Set.of("read"), ToolRisk.READ_ONLY);
        PermissionProfile unrestrictedNetwork = new PermissionProfile(
                safe.id(),
                safe.version(),
                safe.files(),
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), Set.of(443), true),
                safe.processes(),
                safe.tools(),
                safe.resources());

        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity(
                                "permissionProfile/update",
                                "unsafe-network",
                                1,
                                new PermissionProfileRpcContracts.UpdatePayload(unrestrictedNetwork)),
                        unrestrictedNetwork));
        PermissionProfile unrestrictedPorts = new PermissionProfile(
                safe.id(),
                safe.version(),
                safe.files(),
                new NetworkPermission(Set.of("example.invalid"), Set.of(NetworkPermission.ANY_PORT), true),
                safe.processes(),
                safe.tools(),
                safe.resources());
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity(
                                "permissionProfile/update",
                                "unsafe-ports",
                                1,
                                new PermissionProfileRpcContracts.UpdatePayload(unrestrictedPorts)),
                        unrestrictedPorts));
        PermissionProfile unrestrictedFiles = new PermissionProfile(
                safe.id(),
                safe.version(),
                new FilePermission(List.of(Path.of("/")), List.of(Path.of("/")), true, true),
                safe.network(),
                safe.processes(),
                safe.tools(),
                safe.resources());
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity(
                                "permissionProfile/update",
                                "unsafe-files",
                                1,
                                new PermissionProfileRpcContracts.UpdatePayload(unrestrictedFiles)),
                        unrestrictedFiles));
    }

    @Test
    void 有效权限预览逐层求交并解释实时撤权() {
        cloneStandard("runtime", "clone-key");
        PermissionProfile broad = profile("runtime", 2, Set.of("read", "write"), ToolRisk.WORKSPACE_WRITE);
        PermissionProfile narrow = profile("runtime", 3, Set.of("read"), ToolRisk.READ_ONLY);
        update(broad, "broad-key", 1);
        update(narrow, "narrow-key", 2);
        PermissionProfile turnGrant = profile("turn-grant", 1, Set.of("read"), ToolRisk.READ_ONLY);
        PermissionProfile toolDeclaration = profile("tool-declaration", 1, Set.of("read"), ToolRisk.READ_ONLY);

        EffectivePermissionPreview preview = service.preview(
                new PermissionProfileRef("runtime", 2),
                workspace(),
                Optional.of(turnGrant),
                Optional.of(toolDeclaration));

        assertEquals(Set.of("read"), preview.effective().tools().allowedTools());
        assertEquals(
                List.of(PermissionLayerKind.values()),
                preview.layers().stream().map(layer -> layer.layer()).toList());
        assertTrue(preview.layers().get(1).denialReasons().stream().anyMatch(reason -> reason.contains("文件")));
        assertTrue(preview.layers().get(2).denialReasons().stream().anyMatch(reason -> reason.contains("工具")));
        assertTrue(preview.layers().get(3).applied());
        assertTrue(preview.layers().get(4).applied());
        assertFalse(preview.denialReasons().isEmpty());
        assertEquals(preview.effective(), service.resolve("runtime", 2, workspace()));
    }

    private PermissionProfile cloneStandard(String id, String key) {
        PermissionProfileRpcContracts.ClonePayload payload = new PermissionProfileRpcContracts.ClonePayload(
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1), id);
        return service.cloneProfile(
                identity("permissionProfile/clone", key, 0, payload), payload.source(), payload.newId());
    }

    private void update(PermissionProfile profile, String key, long expectedRevision) {
        PermissionProfileRpcContracts.UpdatePayload payload = new PermissionProfileRpcContracts.UpdatePayload(profile);
        service.update(identity("permissionProfile/update", key, expectedRevision, payload), profile);
    }

    private PermissionProfile profile(String id, long version, Set<String> tools, ToolRisk risk) {
        Path root = temporaryDirectory.resolve("workspace").toAbsolutePath().normalize();
        return new PermissionProfile(
                id,
                version,
                new FilePermission(List.of(root), List.of(root), true, false),
                new NetworkPermission(Set.of("example.invalid"), Set.of(443), true),
                new ProcessPermission(Set.of("git"), false, Duration.ofSeconds(5)),
                new ToolPermission(tools, risk, ApprovalRequirement.RISKY),
                new ResourceLimits(8L * 1024 * 1024, 1024 * 1024, 2, 8));
    }

    private static PermissionProfile copy(PermissionProfile source, String id, long version) {
        return new PermissionProfile(
                id, version, source.files(), source.network(), source.processes(), source.tools(), source.resources());
    }

    private Workspace workspace() {
        return new Workspace(
                WorkspaceId.parse("a5b230da-da37-4cbd-8ee3-237e808659c4"),
                "测试",
                temporaryDirectory.resolve("workspace"),
                WorkspaceLifecycle.ACTIVE,
                1,
                NOW,
                NOW);
    }

    private CommandIdentity identity(String method, String key, long expectedRevision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, expectedRevision, json.encode(payload)), json);
    }
}
