package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExecutionRpcContracts;
import com.javaclaw.server.turn.ExecutionPreviewService;

/** 轻量执行配置预览的只读 RPC 映射。 */
public final class ExecutionPreviewRpcHandlers {
    private final ExecutionPreviewService previews;
    private final CanonicalJson json;

    /**
     * 创建配置预览 handler。
     *
     * @param previews 本地配置预览服务
     * @param json 规范 JSON codec
     */
    public ExecutionPreviewRpcHandlers(ExecutionPreviewService previews, CanonicalJson json) {
        this.previews = Objects.requireNonNull(previews, "previews");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册只读执行预览方法。
     *
     * @param routes Router Builder，不可空
     */
    public void register(RpcRouter.Builder routes) {
        Objects.requireNonNull(routes, "routes").register("execution/preview", this::preview);
    }

    private CanonicalPayload preview(CanonicalPayload params) {
        ExecutionRpcContracts.PreviewPayload payload = json.decode(params, ExecutionRpcContracts.PreviewPayload.class);
        return json.encode(previews.preview(payload.workspaceId(), payload.threadId(), payload.execution()));
    }
}
