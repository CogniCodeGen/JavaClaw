package com.javaclaw.server.rpc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolException;
import com.javaclaw.protocol.SessionSecretChannel;

/** 显式方法表；不扫描 classpath，也不作为 Service Locator。 */
public final class RpcRouter {
    private final Map<String, SessionRpcHandler> handlers;

    private RpcRouter(Map<String, SessionRpcHandler> handlers) {
        this.handlers = Map.copyOf(handlers);
    }

    /**
     * 创建路由 Builder。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    CanonicalPayload route(String method, CanonicalPayload params, SessionSecretChannel secrets) throws Exception {
        SessionRpcHandler handler = handlers.get(method);
        if (handler == null) {
            throw new ProtocolException(ProtocolErrorCode.METHOD_NOT_FOUND, "method is not implemented: " + method);
        }
        return handler.handle(params, Objects.requireNonNull(secrets, "secrets"));
    }

    /**
     * 返回组合根实际注册的方法，用于启动自检与架构测试。
     *
     * @return 不可变方法名集合
     */
    public java.util.Set<String> implementedMethods() {
        return handlers.keySet();
    }

    /** 只在 App Server 组合阶段使用的显式 Builder。 */
    public static final class Builder {
        private final Map<String, SessionRpcHandler> handlers = new LinkedHashMap<>();

        private Builder() {}

        /**
         * 注册方法。
         *
         * @param method Protocol v2 方法名
         * @param handler 处理器
         * @return 当前 Builder
         */
        public Builder register(String method, RpcHandler handler) {
            Objects.requireNonNull(handler, "handler");
            return registerSession(method, (params, ignored) -> handler.handle(params));
        }

        /**
         * 注册需要当前连接 Secret 通道的方法。
         *
         * @param method Protocol v2 方法名
         * @param handler 会话感知处理器
         * @return 当前 Builder
         */
        public Builder registerSession(String method, SessionRpcHandler handler) {
            String name = Objects.requireNonNull(method, "method").strip();
            if (name.isEmpty() || handlers.put(name, Objects.requireNonNull(handler, "handler")) != null) {
                throw new IllegalArgumentException("invalid or duplicate RPC method: " + name);
            }
            return this;
        }

        /**
         * 冻结路由表。
         *
         * @return 路由器
         */
        public RpcRouter build() {
            return new RpcRouter(handlers);
        }
    }
}
