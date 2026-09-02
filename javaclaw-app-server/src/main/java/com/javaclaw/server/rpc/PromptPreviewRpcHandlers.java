package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.server.turn.PromptPreviewService;

/** Agent Profile Prompt provenance 查询的薄 RPC 映射。 */
public final class PromptPreviewRpcHandlers {
    private final PromptPreviewService previews;
    private final CanonicalJson json;

    /**
     * 创建 handler。
     *
     * @param previews Prompt 预览服务
     * @param json 规范 JSON codec
     */
    public PromptPreviewRpcHandlers(PromptPreviewService previews, CanonicalJson json) {
        this.previews = Objects.requireNonNull(previews, "previews");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册只读预览方法。
     *
     * @param routes Router Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return Objects.requireNonNull(routes, "routes").register("profile/prompt/preview", this::preview);
    }

    private CanonicalPayload preview(CanonicalPayload params) {
        ProviderProfileRpcContracts.PromptPreviewPayload payload =
                json.decode(params, ProviderProfileRpcContracts.PromptPreviewPayload.class);
        return json.encode(previews.preview(payload.workspaceId(), payload.profile()));
    }
}
