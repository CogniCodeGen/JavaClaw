package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionPresetInstantiationResult;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.security.PermissionPresetCatalog;

/** 将权限预设的预览和实例化映射为 Protocol v2 方法。 */
public final class PermissionPresetRpcHandlers {
    private final CoreCommandService core;
    private final PermissionProfileService profiles;
    private final PermissionPresetCatalog presets;
    private final CanonicalJson json;

    /**
     * 创建 PermissionProfile 预设 handler。
     *
     * @param core Workspace 查询服务
     * @param profiles 权限配置版本服务
     * @param presets 只读预设目录
     * @param json 共享 JSON codec
     */
    public PermissionPresetRpcHandlers(
            CoreCommandService core,
            PermissionProfileService profiles,
            PermissionPresetCatalog presets,
            CanonicalJson json) {
        this.core = Objects.requireNonNull(core, "core");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.presets = Objects.requireNonNull(presets, "presets");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册预设目录、预览和实例化方法。
     *
     * @param routes Router Builder
     */
    public void register(RpcRouter.Builder routes) {
        Objects.requireNonNull(routes, "routes")
                .register("permissionProfile/preset/list", this::list)
                .register("permissionProfile/preset/preview", this::preview)
                .register("permissionProfile/preset/instantiate", this::instantiate);
    }

    private CanonicalPayload list(CanonicalPayload params) {
        requireEmpty(params);
        return json.encode(new PermissionProfileRpcContracts.PresetListResult(presets.list()));
    }

    private CanonicalPayload preview(CanonicalPayload params) {
        PermissionProfileRpcContracts.PresetPreviewPayload payload =
                json.decode(params, PermissionProfileRpcContracts.PresetPreviewPayload.class);
        return json.encode(preview(payload.request()));
    }

    private CanonicalPayload instantiate(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        PermissionProfileRpcContracts.PresetInstantiatePayload payload =
                json.decode(command.payload(), PermissionProfileRpcContracts.PresetInstantiatePayload.class);
        PermissionPresetPreview preview = preview(payload.request());
        var profile = profiles.instantiatePreset(
                CommandIdentity.from("permissionProfile/preset/instantiate", command, json), preview.proposedProfile());
        return json.encode(new PermissionPresetInstantiationResult(preview.preset(), preview.workspaceId(), profile));
    }

    private PermissionPresetPreview preview(com.javaclaw.api.PermissionPresetInstantiationRequest request) {
        Workspace workspace = core.findWorkspace(request.workspaceId())
                .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在"));
        if (workspace.lifecycle() != WorkspaceLifecycle.ACTIVE) {
            throw PersistenceException.invalidRequest("Workspace 当前不可用于权限预设");
        }
        return presets.preview(request, workspace);
    }

    private static void requireEmpty(CanonicalPayload params) {
        if (!"{}".equals(params.json())) {
            throw new IllegalArgumentException("params 必须为空对象");
        }
    }
}
