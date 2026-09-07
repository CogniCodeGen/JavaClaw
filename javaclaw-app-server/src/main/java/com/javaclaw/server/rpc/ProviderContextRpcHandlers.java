package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ModelContextLimits;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderContextRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ProviderContextService;

/** 模型容量独立 RPC，保持已发布 Provider record 的 wire 形状不变。 */
public final class ProviderContextRpcHandlers {
    private final ProviderContextService service;
    private final CanonicalJson json;

    /**
     * 创建模型容量映射。
     *
     * @param service 容量权威服务
     * @param json 规范 codec
     */
    public ProviderContextRpcHandlers(ProviderContextService service, CanonicalJson json) {
        this.service = Objects.requireNonNull(service, "service");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册独立方法。
     *
     * @param routes 路由 Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.register(ProviderContextRpcContracts.READ_METHOD, this::read)
                .register(ProviderContextRpcContracts.UPDATE_METHOD, this::update);
    }

    private CanonicalPayload read(CanonicalPayload params) {
        var request = json.decode(params, ProviderContextRpcContracts.ReadPayload.class);
        return json.encode(service.read(request.provider()));
    }

    private CanonicalPayload update(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        ModelContextLimits limits = json.decode(command.payload(), ModelContextLimits.class);
        return json.encode(
                service.update(CommandIdentity.from(ProviderContextRpcContracts.UPDATE_METHOD, command, json), limits));
    }
}
