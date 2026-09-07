package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.AgentRoleFileRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.AgentRoleFileService;
import com.javaclaw.server.persistence.CommandIdentity;

/** Role 文件操作到受约束服务端用例的薄映射。 */
public final class AgentRoleFileRpcHandlers {
    private final AgentRoleFileService files;
    private final CanonicalJson json;

    /**
     * 创建 Role 文件 handlers。
     *
     * @param files 有界 TOML 解析、预览和提交服务
     * @param json 规范 JSON codec
     */
    public AgentRoleFileRpcHandlers(AgentRoleFileService files, CanonicalJson json) {
        this.files = Objects.requireNonNull(files, "files");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册预览、确认提交与内容导出。
     *
     * @param routes Router Builder
     */
    public void register(RpcRouter.Builder routes) {
        routes.register("agent/role/import/preview", this::preview)
                .register("agent/role/import/commit", this::commit)
                .register("agent/role/export", this::export);
    }

    private CanonicalPayload preview(CanonicalPayload params) {
        AgentRoleFileRpcContracts.PreviewPayload payload =
                json.decode(params, AgentRoleFileRpcContracts.PreviewPayload.class);
        return json.encode(files.preview(payload.roleId(), payload.content(), payload.format()));
    }

    private CanonicalPayload commit(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        AgentRoleFileRpcContracts.CommitPayload payload =
                json.decode(command.payload(), AgentRoleFileRpcContracts.CommitPayload.class);
        return json.encode(files.commit(
                CommandIdentity.from("agent/role/import/commit", command, json),
                payload.previewId(),
                payload.modelMapping()));
    }

    private CanonicalPayload export(CanonicalPayload params) {
        AgentRoleFileRpcContracts.ExportPayload payload =
                json.decode(params, AgentRoleFileRpcContracts.ExportPayload.class);
        return json.encode(files.export(payload.role(), payload.format()));
    }
}
