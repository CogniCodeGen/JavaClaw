package com.javaclaw.server.rpc;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ProviderModelDiscoveryRequest;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderModelDiscoveryRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ProviderModelDiscoveryService;

/** Provider 模型目录发现临时操作的薄 RPC 映射。 */
public final class ProviderModelDiscoveryRpcHandlers {
    private final ProviderModelDiscoveryService service;
    private final CanonicalJson json;
    private final Set<String> registeredSessions = ConcurrentHashMap.newKeySet();

    /**
     * 创建处理器。
     *
     * @param service 模型目录发现服务
     * @param json 规范 JSON codec
     */
    public ProviderModelDiscoveryRpcHandlers(ProviderModelDiscoveryService service, CanonicalJson json) {
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
        return routes.registerSession(ProviderModelDiscoveryRpcContracts.START_METHOD, this::start)
                .registerSession(ProviderModelDiscoveryRpcContracts.READ_METHOD, this::read)
                .registerSession(ProviderModelDiscoveryRpcContracts.CANCEL_METHOD, this::cancel);
    }

    private CanonicalPayload start(CanonicalPayload params, SessionSecretChannel session) {
        registerClose(session);
        WriteCommand command = json.decode(params, WriteCommand.class);
        ProviderModelDiscoveryRequest request = json.decode(command.payload(), ProviderModelDiscoveryRequest.class);
        var operation = service.start(
                session.sessionId(),
                CommandIdentity.from(ProviderModelDiscoveryRpcContracts.START_METHOD, command, json),
                request);
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
                CommandIdentity.from(ProviderModelDiscoveryRpcContracts.CANCEL_METHOD, command, json),
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
