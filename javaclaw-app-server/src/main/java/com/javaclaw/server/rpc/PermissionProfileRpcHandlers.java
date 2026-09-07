package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.PersistenceException;

/** PermissionProfile 管理方法到版本服务的独立 RPC 映射。 */
public final class PermissionProfileRpcHandlers {
    private final CoreCommandService core;
    private final PermissionProfileService profiles;
    private final CanonicalJson json;

    /**
     * 创建 PermissionProfile handlers。
     *
     * @param core Workspace 查询服务
     * @param profiles 权限配置服务
     * @param json 共享 JSON codec
     */
    public PermissionProfileRpcHandlers(
            CoreCommandService core, PermissionProfileService profiles, CanonicalJson json) {
        this.core = Objects.requireNonNull(core, "core");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册 PermissionProfile Protocol v3 方法。
     *
     * @param routes Router Builder
     */
    public void register(RpcRouter.Builder routes) {
        Objects.requireNonNull(routes, "routes")
                .register("permissionProfile/list", this::list)
                .register("permissionProfile/read", this::read)
                .register("permissionProfile/history", this::history)
                .register("permissionProfile/clone", this::cloneProfile)
                .register("permissionProfile/update", this::update)
                .register("permissionProfile/diff", this::diff)
                .register("permissionProfile/effectivePreview", this::effectivePreview);
    }

    private CanonicalPayload list(CanonicalPayload params) {
        requireEmpty(params);
        return json.encode(new PermissionProfileRpcContracts.ListResult(profiles.listLatest()));
    }

    private CanonicalPayload read(CanonicalPayload params) {
        PermissionProfileRpcContracts.ReadPayload payload =
                json.decode(params, PermissionProfileRpcContracts.ReadPayload.class);
        return json.encode(
                profiles.require(payload.reference().id(), payload.reference().version()));
    }

    private CanonicalPayload history(CanonicalPayload params) {
        PermissionProfileRpcContracts.HistoryPayload payload =
                json.decode(params, PermissionProfileRpcContracts.HistoryPayload.class);
        return json.encode(new PermissionProfileRpcContracts.HistoryResult(profiles.history(payload.id())));
    }

    private CanonicalPayload cloneProfile(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        PermissionProfileRpcContracts.ClonePayload payload =
                json.decode(command.payload(), PermissionProfileRpcContracts.ClonePayload.class);
        return json.encode(profiles.cloneProfile(
                CommandIdentity.from("permissionProfile/clone", command, json), payload.source(), payload.newId()));
    }

    private CanonicalPayload update(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        PermissionProfileRpcContracts.UpdatePayload payload =
                json.decode(command.payload(), PermissionProfileRpcContracts.UpdatePayload.class);
        return json.encode(
                profiles.update(CommandIdentity.from("permissionProfile/update", command, json), payload.profile()));
    }

    private CanonicalPayload diff(CanonicalPayload params) {
        PermissionProfileRpcContracts.DiffPayload payload =
                json.decode(params, PermissionProfileRpcContracts.DiffPayload.class);
        return json.encode(profiles.diff(payload.id(), payload.beforeVersion(), payload.afterVersion()));
    }

    private CanonicalPayload effectivePreview(CanonicalPayload params) {
        PermissionProfileRpcContracts.EffectivePreviewPayload payload =
                json.decode(params, PermissionProfileRpcContracts.EffectivePreviewPayload.class);
        return json.encode(profiles.preview(
                payload.profile(),
                core.findWorkspace(payload.workspaceId())
                        .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在")),
                payload.turnGrant(),
                payload.toolDeclaration()));
    }

    private void requireEmpty(CanonicalPayload params) {
        if (!"{}".equals(params.json())) {
            throw PersistenceException.invalidRequest("params 必须为空对象");
        }
    }
}
