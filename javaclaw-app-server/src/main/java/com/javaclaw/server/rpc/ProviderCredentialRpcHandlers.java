package com.javaclaw.server.rpc;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderCredentialRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ProviderCredentialService;

/** Provider 配置与 Vault Secret 原子复合命令的 RPC 处理器。 */
public final class ProviderCredentialRpcHandlers {
    private static final String SET_METHOD = "provider/credential/set";
    private static final String CLEAR_METHOD = "provider/credential/clear";

    private final ProviderCredentialService service;
    private final CanonicalJson json;

    /**
     * 创建处理器。
     *
     * @param service 原子复合应用服务
     * @param json 规范 JSON codec
     */
    public ProviderCredentialRpcHandlers(ProviderCredentialService service, CanonicalJson json) {
        this.service = Objects.requireNonNull(service, "service");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册两个破坏性替换后的 Provider Secret 方法。
     *
     * @param routes 路由 Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.registerSession(SET_METHOD, this::set).register(CLEAR_METHOD, this::clear);
    }

    private CanonicalPayload set(CanonicalPayload params, SessionSecretChannel secrets) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CommandIdentity identity = CommandIdentity.from(SET_METHOD, command, json);
        Optional<ProviderCredentialBinding> replay = service.recoverSet(identity);
        if (replay.isPresent()) {
            return json.encode(replay.orElseThrow());
        }
        ProviderCredentialRpcContracts.SetPayload payload =
                json.decode(command.payload(), ProviderCredentialRpcContracts.SetPayload.class);
        byte[] plaintext = secrets.unseal(payload.secret(), ProviderCredentialRpcContracts.SET_PURPOSE);
        try {
            return json.encode(service.set(
                    identity,
                    payload.providerId(),
                    payload.providerExpectedRevision(),
                    payload.credentialExpectedRevision(),
                    plaintext));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private CanonicalPayload clear(CanonicalPayload params) {
        WriteCommand command = json.decode(params, WriteCommand.class);
        CommandIdentity identity = CommandIdentity.from(CLEAR_METHOD, command, json);
        Optional<ProviderCredentialClearResult> replay = service.recoverClear(identity);
        if (replay.isPresent()) {
            return json.encode(replay.orElseThrow());
        }
        ProviderCredentialRpcContracts.ClearPayload payload =
                json.decode(command.payload(), ProviderCredentialRpcContracts.ClearPayload.class);
        return json.encode(service.clear(
                identity,
                payload.providerId(),
                payload.providerExpectedRevision(),
                payload.credential(),
                payload.credentialExpectedRevision()));
    }
}
