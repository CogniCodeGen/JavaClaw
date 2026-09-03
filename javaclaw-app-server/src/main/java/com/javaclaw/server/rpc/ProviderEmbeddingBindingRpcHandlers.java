package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.EmbeddingBindingService;

/** 本地安装级 Embedding 精确绑定的薄 RPC 映射。 */
public final class ProviderEmbeddingBindingRpcHandlers {
    private static final String READ_METHOD = "provider/embeddingBinding/read";
    private static final String UPDATE_METHOD = "provider/embeddingBinding/update";

    private final EmbeddingBindingService service;
    private final CanonicalJson json;

    /**
     * 创建处理器。
     *
     * @param service 安装级绑定服务
     * @param json 规范 JSON codec
     */
    public ProviderEmbeddingBindingRpcHandlers(EmbeddingBindingService service, CanonicalJson json) {
        this.service = Objects.requireNonNull(service, "service");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册查询与更新方法。
     *
     * @param routes 路由 Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.register(READ_METHOD, this::read).register(UPDATE_METHOD, this::update);
    }

    private CanonicalPayload read(CanonicalPayload params) {
        json.decode(params, ProviderProfileRpcContracts.EmbeddingBindingReadPayload.class);
        return json.encode(new ProviderProfileRpcContracts.EmbeddingBindingReadResult(service.find()));
    }

    private CanonicalPayload update(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        ProviderProfileRpcContracts.EmbeddingBindingUpdatePayload payload =
                json.decode(command.payload(), ProviderProfileRpcContracts.EmbeddingBindingUpdatePayload.class);
        return json.encode(service.update(CommandIdentity.from(UPDATE_METHOD, command, json), payload.provider()));
    }
}
