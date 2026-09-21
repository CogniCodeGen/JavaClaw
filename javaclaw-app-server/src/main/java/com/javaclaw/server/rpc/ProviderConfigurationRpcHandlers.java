package com.javaclaw.server.rpc;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ProviderConfigurationResult;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ProviderConfigurationService;

/** Provider 完整配置保存与脱敏结果恢复的薄 RPC 映射；恢复成功时不再次解封 Secret。 */
public final class ProviderConfigurationRpcHandlers {
    private final ProviderConfigurationService service;
    private final CanonicalJson json;

    /**
     * 创建完整配置处理器。
     *
     * @param service 配置与 Vault 的原子保存服务
     * @param json 规范 JSON 编解码器
     */
    public ProviderConfigurationRpcHandlers(ProviderConfigurationService service, CanonicalJson json) {
        this.service = Objects.requireNonNull(service, "service");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册保存与查询原保存结果两个方法。
     *
     * @param routes 当前 RPC 路由构建器
     * @return 同一路由构建器
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.registerSession(ProviderConfigurationRpcContracts.SAVE_METHOD, this::save)
                .register(ProviderConfigurationRpcContracts.RESULT_METHOD, this::result);
    }

    private CanonicalPayload save(CanonicalPayload params, SessionSecretChannel secrets) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CommandIdentity identity = CommandIdentity.from(ProviderConfigurationRpcContracts.SAVE_METHOD, command, json);
        Optional<ProviderConfigurationResult> recovered = service.recover(identity);
        if (recovered.isPresent()) {
            return json.encode(recovered.orElseThrow());
        }
        var payload = json.decode(command.payload(), ProviderConfigurationRpcContracts.SavePayload.class);
        byte[] plaintext = payload.secret()
                .map(secret -> secrets.unseal(secret, ProviderConfigurationRpcContracts.SAVE_PURPOSE))
                .orElse(null);
        try {
            return json.encode(service.save(identity, payload.configuration(), plaintext));
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private CanonicalPayload result(CanonicalPayload params) {
        var payload = json.decode(params, ProviderConfigurationRpcContracts.ResultPayload.class);
        CommandIdentity identity = new CommandIdentity(
                ProviderConfigurationRpcContracts.SAVE_METHOD,
                payload.idempotencyKey(),
                payload.expectedRevision(),
                payload.requestDigest());
        return json.encode(new ProviderConfigurationRpcContracts.ResultResponse(service.recover(identity)));
    }
}
