package com.javaclaw.server.rpc;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.ProviderModelDiscoveryRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ProviderModelPreviewService;

/** Provider 连接草稿预览的薄 RPC 映射；解封由业务服务在幂等检查后按需触发。 */
public final class ProviderModelPreviewRpcHandlers {
    private final ProviderModelPreviewService service;
    private final CanonicalJson json;
    private final Set<String> registeredSessions = ConcurrentHashMap.newKeySet();

    /**
     * 创建处理器。
     *
     * @param service 模型目录发现服务
     * @param json 规范 JSON codec
     */
    public ProviderModelPreviewRpcHandlers(ProviderModelPreviewService service, CanonicalJson json) {
        this.service = Objects.requireNonNull(service, "service");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 注册 start、read 与 cancel 三个窄方法。
     *
     * @param routes 路由 Builder
     * @return 当前 Builder
     */
    public RpcRouter.Builder register(RpcRouter.Builder routes) {
        return routes.registerSession(ProviderConfigurationRpcContracts.PREVIEW_START_METHOD, this::start)
                .registerSession(ProviderConfigurationRpcContracts.PREVIEW_READ_METHOD, this::read)
                .registerSession(ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD, this::cancel);
    }

    private CanonicalPayload start(CanonicalPayload params, SessionSecretChannel session) {
        registerClose(session);
        WriteCommand command = json.decode(params, WriteCommand.class);
        ProviderConfigurationRpcContracts.PreviewPayload payload =
                json.decode(command.payload(), ProviderConfigurationRpcContracts.PreviewPayload.class);
        var operation = service.start(
                session.sessionId(),
                CommandIdentity.from(ProviderConfigurationRpcContracts.PREVIEW_START_METHOD, command, json),
                payload.request(),
                () -> session.unseal(
                        payload.secret().orElseThrow(), ProviderConfigurationRpcContracts.PREVIEW_PURPOSE));
        if (session.isClosed()) {
            service.cancelOwner(session.sessionId());
        }
        return json.encode(operation);
    }

    private CanonicalPayload read(CanonicalPayload params, SessionSecretChannel session) {
        registerClose(session);
        ProviderModelDiscoveryRpcContracts.ReadPayload request =
                json.decode(params, ProviderModelDiscoveryRpcContracts.ReadPayload.class);
        return json.encode(service.read(session.sessionId(), request.operationId()));
    }

    private CanonicalPayload cancel(CanonicalPayload params, SessionSecretChannel session) {
        registerClose(session);
        WriteCommand command = json.decode(params, WriteCommand.class);
        ProviderModelDiscoveryRpcContracts.CancelPayload request =
                json.decode(command.payload(), ProviderModelDiscoveryRpcContracts.CancelPayload.class);
        return json.encode(service.cancel(
                session.sessionId(),
                CommandIdentity.from(ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD, command, json),
                request.operationId(),
                request.reason()));
    }

    boolean registerClose(SessionSecretChannel session) {
        String sessionId = session.sessionId();
        if (!registeredSessions.add(sessionId)) {
            return false;
        }
        session.onClose(() -> {
            try {
                service.cancelOwner(sessionId);
            } finally {
                registeredSessions.remove(sessionId);
            }
        });
        return true;
    }
}
