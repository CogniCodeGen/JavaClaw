package com.javaclaw.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionLayerKind;
import com.javaclaw.api.PermissionLayerResult;
import com.javaclaw.api.PermissionPresetDescriptor;
import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.PermissionPresetInstantiationResult;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PermissionSection;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.facade.PermissionProfileClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionProfileClientTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("d66bf633-2166-456e-9a15-28780e994808");

    @Test
    void facade为七个管理方法保持强类型和写命令信封() {
        PermissionProfile first = profile("developer", 1, Set.of("read"));
        PermissionProfile second = profile("developer", 2, Set.of("read", "write"));
        PermissionProfileDiff diff = new PermissionProfileDiff(first, second, Set.of(PermissionSection.TOOL));
        EffectivePermissionPreview preview = preview(second);
        List<String> called = new ArrayList<>();
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> {
            called.add(request.method());
            return response(request, first, second, diff, preview);
        });
        PermissionProfileClient client = new PermissionProfileClient(new RpcClientConnection(rpc, JSON, ignored -> {}));

        assertEquals(List.of(second), client.list());
        assertEquals(second, client.read(new PermissionProfileRef(second.id(), second.version())));
        assertEquals(List.of(first, second), client.history(second.id()));
        assertEquals(
                first,
                client.cloneProfile(
                        new PermissionProfileRef("standard", 1), "developer", new CommandOptions("clone", 0)));
        assertEquals(second, client.update(second, new CommandOptions("update", 1)));
        assertEquals(diff, client.diff("developer", 1, 2));
        assertEquals(
                preview,
                client.effectivePreview(
                        WORKSPACE, new PermissionProfileRef("developer", 2), Optional.empty(), Optional.empty()));
        assertEquals(7, called.size());
    }

    @Test
    void facade以固定Workspace预览并实例化权限预设() {
        PermissionPresetDescriptor preset =
                new PermissionPresetDescriptor("workspace-review", 1, "只读审查", "只读 Workspace", false);
        PermissionPresetInstantiationRequest request = new PermissionPresetInstantiationRequest(
                preset.id(), preset.revision(), WORKSPACE, "review-profile", Set.of("read"), Set.of());
        PermissionProfile profile = profile(request.profileId(), 1, Set.of("read"));
        PermissionPresetPreview preview = new PermissionPresetPreview(preset, WORKSPACE, profile, List.of());
        PermissionPresetInstantiationResult result =
                new PermissionPresetInstantiationResult(preset, WORKSPACE, profile);
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(rpcRequest -> switch (rpcRequest.method()) {
            case "permissionProfile/preset/list" ->
                JsonRpcResponse.success(
                        rpcRequest.id(),
                        JSON.encode(new PermissionProfileRpcContracts.PresetListResult(List.of(preset))));
            case "permissionProfile/preset/preview" -> JsonRpcResponse.success(rpcRequest.id(), JSON.encode(preview));
            case "permissionProfile/preset/instantiate" -> {
                assertCommand(rpcRequest, "instantiate", 0);
                yield JsonRpcResponse.success(rpcRequest.id(), JSON.encode(result));
            }
            default -> throw new AssertionError("unexpected method " + rpcRequest.method());
        });
        PermissionProfileClient client = new PermissionProfileClient(new RpcClientConnection(rpc, JSON, ignored -> {}));

        assertEquals(List.of(preset), client.presets());
        assertEquals(preview, client.previewPreset(request));
        assertEquals(result, client.instantiatePreset(request, new CommandOptions("instantiate", 0)));
    }

    private static JsonRpcResponse response(
            JsonRpcRequest request,
            PermissionProfile first,
            PermissionProfile second,
            PermissionProfileDiff diff,
            EffectivePermissionPreview preview) {
        return switch (request.method()) {
            case "permissionProfile/list" ->
                success(request, new PermissionProfileRpcContracts.ListResult(List.of(second)));
            case "permissionProfile/read" -> success(request, second);
            case "permissionProfile/history" ->
                success(request, new PermissionProfileRpcContracts.HistoryResult(List.of(first, second)));
            case "permissionProfile/clone" -> {
                assertCommand(request, "clone", 0);
                yield success(request, first);
            }
            case "permissionProfile/update" -> {
                assertCommand(request, "update", 1);
                yield success(request, second);
            }
            case "permissionProfile/diff" -> success(request, diff);
            case "permissionProfile/effectivePreview" -> success(request, preview);
            default -> throw new AssertionError("unexpected method " + request.method());
        };
    }

    private static void assertCommand(JsonRpcRequest request, String key, long expectedRevision) {
        WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
        assertEquals(key, command.idempotencyKey());
        assertEquals(expectedRevision, command.expectedRevision());
    }

    private static JsonRpcResponse success(JsonRpcRequest request, Object result) {
        return JsonRpcResponse.success(request.id(), JSON.encode(result));
    }

    private static EffectivePermissionPreview preview(PermissionProfile effective) {
        List<PermissionLayerResult> layers = Arrays.stream(PermissionLayerKind.values())
                .map(layer -> new PermissionLayerResult(layer, Optional.empty(), true, effective, List.of()))
                .toList();
        return new EffectivePermissionPreview(effective, layers, List.of());
    }

    private static PermissionProfile profile(String id, long version, Set<String> tools) {
        return new PermissionProfile(
                id,
                version,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                new ToolPermission(tools, ToolRisk.READ_ONLY, ApprovalRequirement.RISKY),
                new ResourceLimits(1024, 1024, 1, 1));
    }
}
