package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionPresetDescriptor;
import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.PermissionPresetInstantiationResult;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionProfileRpcContractsTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("f905fe10-3fbb-4bff-a523-101d78746f65");
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 管理DTO按精确版本往返并复制列表() {
        PermissionProfile profile = profile("developer", 2);
        PermissionProfileRpcContracts.EffectivePreviewPayload preview =
                new PermissionProfileRpcContracts.EffectivePreviewPayload(
                        WORKSPACE,
                        new PermissionProfileRef("developer", 2),
                        Optional.of(profile("turn-grant", 1)),
                        Optional.empty());

        assertEquals(
                preview,
                json.decode(json.encode(preview), PermissionProfileRpcContracts.EffectivePreviewPayload.class));
        assertEquals(
                new PermissionProfileRef("standard", 1),
                new PermissionProfileRpcContracts.ReadPayload(new PermissionProfileRef("standard", 1)).reference());
        assertEquals("developer", new PermissionProfileRpcContracts.HistoryPayload(" developer ").id());
        assertEquals(List.of(profile), new PermissionProfileRpcContracts.ListResult(List.of(profile)).profiles());
        assertEquals(List.of(profile), new PermissionProfileRpcContracts.HistoryResult(List.of(profile)).profiles());
    }

    @Test
    void 克隆更新和差异DTO拒绝无效输入() {
        PermissionProfile profile = profile("developer", 2);

        assertEquals(
                "developer",
                new PermissionProfileRpcContracts.ClonePayload(new PermissionProfileRef("standard", 1), " developer ")
                        .newId());
        assertEquals(profile, new PermissionProfileRpcContracts.UpdatePayload(profile).profile());
        assertEquals(2, new PermissionProfileRpcContracts.DiffPayload("developer", 1, 2).afterVersion());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PermissionProfileRpcContracts.ClonePayload(
                        new PermissionProfileRef("standard", 1), "bad id"));
        assertThrows(
                IllegalArgumentException.class, () -> new PermissionProfileRpcContracts.DiffPayload("developer", 0, 2));
        assertThrows(NullPointerException.class, () -> new PermissionProfileRpcContracts.UpdatePayload(null));
        assertThrows(
                NullPointerException.class,
                () -> new PermissionProfileRpcContracts.EffectivePreviewPayload(
                        WORKSPACE, new PermissionProfileRef("developer", 2), null, Optional.empty()));
    }

    @Test
    void 权限预设请求预览和实例化结果保持固定Workspace() {
        PermissionPresetDescriptor preset =
                new PermissionPresetDescriptor("workspace-review", 1, "只读审查", "只读 Workspace", false);
        PermissionPresetInstantiationRequest request = new PermissionPresetInstantiationRequest(
                preset.id(), preset.revision(), WORKSPACE, "workspace-review-profile", Set.of("read"), Set.of());
        PermissionProfile profile = profile(request.profileId(), 1);
        PermissionPresetPreview preview = new PermissionPresetPreview(preset, WORKSPACE, profile, List.of());
        PermissionPresetInstantiationResult result =
                new PermissionPresetInstantiationResult(preset, WORKSPACE, profile);

        assertEquals(
                new PermissionProfileRpcContracts.PresetPreviewPayload(request),
                json.decode(
                        json.encode(new PermissionProfileRpcContracts.PresetPreviewPayload(request)),
                        PermissionProfileRpcContracts.PresetPreviewPayload.class));
        assertEquals(preview, json.decode(json.encode(preview), PermissionPresetPreview.class));
        assertEquals(result, json.decode(json.encode(result), PermissionPresetInstantiationResult.class));
        assertEquals(List.of(preset), new PermissionProfileRpcContracts.PresetListResult(List.of(preset)).presets());
    }

    @Test
    void 方法目录和逐方法Schema声明完整管理面() throws IOException {
        NegotiatedCapabilities none = new NegotiatedCapabilities(Set.of(), Set.of());

        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require("permissionProfile/clone", none).kind());
        assertEquals(
                RpcMethodKind.QUERY,
                MethodCatalog.require("permissionProfile/effectivePreview", none)
                        .kind());
        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require("permissionProfile/preset/instantiate", none)
                        .kind());
        String schema = read("/schema/permission-profile-v2.schema.json");
        String methods = read("/schema/methods-v2.json");
        json.parse(schema);
        json.parse(methods);
        assertTrue(schema.contains("effectivePermissionPreview"));
        assertTrue(methods.contains("permission-profile-v2.schema.json#/$defs/diffPayload"));
    }

    private static PermissionProfile profile(String id, long version) {
        return new PermissionProfile(
                id,
                version,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of("example.invalid"), Set.of(443), true),
                new ProcessPermission(Set.of("git"), false, Duration.ofSeconds(10)),
                new ToolPermission(Set.of("tool_search"), ToolRisk.READ_ONLY, ApprovalRequirement.RISKY),
                new ResourceLimits(1024, 1024, 1, 1));
    }

    private static String read(String resource) throws IOException {
        try (var input = PermissionProfileRpcContractsTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing resource " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
