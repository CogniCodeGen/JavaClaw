package com.javaclaw.sdk;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.sdk.model.WorkspaceInfo;

/** 工作区登记领域客户端；不直接读取 H2，远程失败通过 Future 异常返回。 */
public final class WorkspaceClient {
    private final ProtocolClient protocol;
    private final SdkProtocolMapper mapper;

    WorkspaceClient(ProtocolClient protocol, SdkProtocolMapper mapper) {
        this.protocol = protocol;
        this.mapper = mapper;
    }

    /** 读取服务端已登记的工作区列表，不扫描本地目录推测工作区。 */
    public CompletableFuture<List<WorkspaceInfo>> list() {
        return protocol.listWorkspaces()
                .thenApply(values -> values.stream().map(mapper::workspace).toList());
    }

    /** 请求登记规范化工作区根目录；这是 Workspace 管理操作，不允许每次 Turn 任意传 cwd。 */
    public CompletableFuture<WorkspaceInfo> create(String name, Path root, String idempotencyKey) {
        return protocol.createWorkspace(name, root, idempotencyKey).thenApply(mapper::workspace);
    }

    /** 读取工作区及安全锁状态；不存在时 Future 以 RPC 错误完成。 */
    public CompletableFuture<WorkspaceInfo> read(String id) {
        return protocol.readWorkspace(id).thenApply(mapper::workspace);
    }

    /** 解析工作区当前有效 AGENTS.md 链，只返回来源元数据和警告，不返回或保存正文。 */
    public CompletableFuture<com.javaclaw.sdk.model.AgentsInstructionResolutionInfo> resolveInstructions(
            String workspaceId) {
        return protocol.resolveInstructions(workspaceId).thenApply(mapper::instructions);
    }

    /** 按预期版本更新展示名称，不改变工作区根路径；幂等键用于去重。 */
    public CompletableFuture<WorkspaceInfo> update(
            String id, String name, long expectedRevision, String idempotencyKey) {
        return protocol.updateWorkspace(id, name, expectedRevision, idempotencyKey)
                .thenApply(mapper::workspace);
    }

    /** 按版本删除工作区登记，不删除用户项目文件；仍有 Thread 关联时服务端拒绝。 */
    public CompletableFuture<Boolean> delete(String id, long expectedRevision, String idempotencyKey) {
        return protocol.deleteWorkspace(id, expectedRevision, idempotencyKey);
    }
}
