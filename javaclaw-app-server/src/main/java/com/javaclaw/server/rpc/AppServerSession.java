package com.javaclaw.server.rpc;

import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InitializeResult;
import com.javaclaw.protocol.JsonRpcError;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.MethodCatalog;
import com.javaclaw.protocol.NegotiatedCapabilities;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolException;
import com.javaclaw.protocol.ProtocolNegotiator;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.RpcMethod;
import com.javaclaw.protocol.RpcMethodKind;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.persistence.PersistenceException;

/** 单条本地连接的 initialize、能力协商和请求路由状态。 */
public final class AppServerSession implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppServerSession.class);

    private final ProtocolNegotiator negotiator;
    private final RpcRouter router;
    private final CanonicalJson json;
    private final LifecycleCoordinator lifecycle;
    private final SessionSecretChannel secrets;
    private final ExtensionEventHub events;
    private NegotiatedCapabilities capabilities;

    /**
     * 创建会话。
     *
     * @param negotiator 能力协商器
     * @param router 方法路由
     * @param json 共享 JSON codec
     * @param lifecycle 后台生命周期协调器
     * @param events Extension 状态失效通知
     */
    public AppServerSession(
            ProtocolNegotiator negotiator,
            RpcRouter router,
            CanonicalJson json,
            LifecycleCoordinator lifecycle,
            ExtensionEventHub events) {
        this.negotiator = Objects.requireNonNull(negotiator, "negotiator");
        this.router = Objects.requireNonNull(router, "router");
        this.json = Objects.requireNonNull(json, "json");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.events = Objects.requireNonNull(events, "events");
        secrets = SessionSecretChannel.open();
    }

    /**
     * 处理一条 request，并始终生成相同 ID 的 response。
     *
     * @param request request
     * @return success 或安全错误
     */
    public JsonRpcResponse handle(JsonRpcRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            if ("initialize/session".equals(request.method())) {
                return initialize(request);
            }
            requireInitialized();
            RpcMethod method = MethodCatalog.require(request.method(), capabilities);
            requireClientCallable(method);
            requireWriteEnvelope(method, request.params());
            return JsonRpcResponse.success(request.id(), router.route(request.method(), request.params(), secrets));
        } catch (ProtocolException failure) {
            return failure(request, failure.code(), failure.getMessage());
        } catch (SecurityException failure) {
            return failure(request, ProtocolErrorCode.PERMISSION_DENIED, failure.getMessage());
        } catch (IllegalArgumentException failure) {
            return failure(request, ProtocolErrorCode.INVALID_PARAMS, failure.getMessage());
        } catch (PersistenceException failure) {
            return persistenceFailure(request, failure);
        } catch (Exception failure) {
            LOGGER.error("RPC {} failed", request.method(), failure);
            return failure(request, ProtocolErrorCode.INTERNAL_ERROR, "internal server error");
        }
    }

    /**
     * 在当前线程服务连接直至 EOF。
     *
     * @param connection 已建立连接，所有权保留给调用方
     * @throws IOException 发送或非 EOF 读取失败
     */
    public void serve(RpcConnection connection) throws IOException {
        Objects.requireNonNull(connection, "connection");
        ExtensionEventHub.Subscription eventSubscription = null;
        try (LifecycleCoordinator.Lease ignored = lifecycle.clientConnected();
                SessionSecretChannel ignoredSecrets = secrets) {
            while (true) {
                JsonRpcMessage message;
                try {
                    message = connection.receive();
                } catch (EOFException endOfStream) {
                    return;
                }
                if (message instanceof JsonRpcRequest request) {
                    JsonRpcResponse response = handle(request);
                    connection.send(response);
                    if (eventSubscription == null
                            && "initialize/session".equals(request.method())
                            && response.error().isEmpty()) {
                        eventSubscription = events.subscribe(connection);
                    }
                } else if (message instanceof JsonRpcNotification notification) {
                    rejectClientNotification(notification);
                } else {
                    throw new IOException("client must not send JSON-RPC responses to the server");
                }
            }
        } finally {
            if (eventSubscription != null) {
                eventSubscription.close();
            }
        }
    }

    private JsonRpcResponse initialize(JsonRpcRequest request) {
        if (capabilities != null) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_REQUEST, "session is already initialized");
        }
        InitializeParams params = json.decode(request.params(), InitializeParams.class);
        capabilities = negotiator.negotiate(params);
        InitializeResult result = new InitializeResult(
                ProtocolVersion.CURRENT, "JavaClaw App Server", "5.0.0-SNAPSHOT", capabilities, secrets.publicKey());
        return JsonRpcResponse.success(request.id(), json.encode(result));
    }

    /** 关闭当前连接的 Secret 私钥；重复关闭安全。 */
    @Override
    public void close() {
        secrets.close();
    }

    private void requireInitialized() {
        if (capabilities == null) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_REQUEST, "initialize/session must be called first");
        }
    }

    private static void requireClientCallable(RpcMethod method) {
        if (method.kind() == RpcMethodKind.NOTIFICATION) {
            throw new ProtocolException(ProtocolErrorCode.METHOD_NOT_FOUND, "server notification is not callable");
        }
    }

    private void requireWriteEnvelope(RpcMethod method, CanonicalPayload params) {
        if (method.kind() == RpcMethodKind.COMMAND) {
            json.decode(params, WriteCommand.class);
        }
    }

    private static void rejectClientNotification(JsonRpcNotification notification) throws IOException {
        throw new IOException("client notification is not supported: " + notification.method());
    }

    private JsonRpcResponse persistenceFailure(JsonRpcRequest request, PersistenceException failure) {
        return switch (failure.kind()) {
            case INVALID_REQUEST -> failure(request, ProtocolErrorCode.INVALID_PARAMS, failure.getMessage());
            case REVISION_CONFLICT -> failure(request, ProtocolErrorCode.REVISION_CONFLICT, failure.getMessage());
            case IDEMPOTENCY_CONFLICT -> failure(request, ProtocolErrorCode.IDEMPOTENCY_CONFLICT, failure.getMessage());
            case INTERNAL -> {
                LOGGER.error("RPC {} persistence failed", request.method(), failure);
                yield failure(request, ProtocolErrorCode.INTERNAL_ERROR, "internal persistence error");
            }
        };
    }

    private JsonRpcResponse failure(JsonRpcRequest request, int code, String message) {
        JsonRpcError error = new JsonRpcError(code, safeMessage(message), Optional.empty());
        return JsonRpcResponse.failure(request.id(), error);
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "request failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
