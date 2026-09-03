package com.javaclaw.server.rpc;

import java.util.Objects;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ProviderVerificationService;

/** 显式计费 Provider round-trip 的薄 RPC 映射。 */
public final class ProviderVerificationRpcHandlers {
    private final ProviderVerificationService service;
    private final CanonicalJson json;

    /**
     * 创建处理器。
     *
     * @param service 幂等验证服务
     * @param json 规范 JSON codec
     */
    public ProviderVerificationRpcHandlers(ProviderVerificationService service, CanonicalJson json) {
        this.service = Objects.requireNonNull(service, "service");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册唯一显式计费验证命令。
     *
     * @param routes 路由 Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.register(ProviderVerificationRpcContracts.METHOD, this::verify);
    }

    private CanonicalPayload verify(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        ProviderVerificationRpcContracts.VerifyPayload payload =
                json.decode(command.payload(), ProviderVerificationRpcContracts.VerifyPayload.class);
        CommandIdentity identity = CommandIdentity.from(ProviderVerificationRpcContracts.METHOD, command, json);
        return json.encode(service.verify(identity, payload.provider(), payload.purpose(), new CancellationSource()));
    }
}
