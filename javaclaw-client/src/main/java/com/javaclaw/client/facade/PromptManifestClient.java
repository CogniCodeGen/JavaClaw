package com.javaclaw.client.facade;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.PromptManifestRpcContracts;

/** 服务端统一解析后的 Prompt 分层来源预览客户端。 */
public final class PromptManifestClient {
    private final RpcClientConnection connection;

    /**
     * 创建只读客户端。
     *
     * @param connection 已初始化的 RPC 连接
     */
    public PromptManifestClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 预览下一 Turn 的来源、版本、摘要与 token 估算。
     *
     * @param workspaceId 项目约定所属 Workspace
     * @param threadId 可选 Thread 执行覆盖
     * @param execution 显式执行选择
     * @return 可审阅的 Prompt 来源；不写入 Turn 或调用付费模型
     */
    public PromptManifestPreview preview(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, ExecutionOverrides execution) {
        return connection.query(
                "prompt/manifest/preview",
                new PromptManifestRpcContracts.PreviewPayload(workspaceId, threadId, execution),
                PromptManifestPreview.class);
    }
}
