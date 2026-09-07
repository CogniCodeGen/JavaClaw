package com.javaclaw.model;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.NativeCompactionRequest;
import com.javaclaw.runtime.NativeCompactionResult;
import com.javaclaw.runtime.NativeCompactionSupport;
import com.javaclaw.runtime.NativeConversationSupport;
import com.javaclaw.runtime.ProviderState;

/**
 * 按稳定 endpoint ID 路由 Spring AI 与 Provider 原生适配器。
 *
 * <p>实现不变量：路由表构造后不可变；同一个底层适配器可服务多个端点，但关闭时只释放一次。
 */
public final class ModelGatewayRouter
        implements ModelGateway, NativeConversationSupport, NativeCompactionSupport, AutoCloseable {
    private final Map<String, ModelGateway> routes;

    private ModelGatewayRouter(Map<String, ModelGateway> routes) {
        this.routes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(routes));
    }

    /**
     * 创建路由组合器。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ModelCapabilities capabilities(String modelId) {
        return route(modelId).capabilities(modelId);
    }

    @Override
    public ModelInvocationResult invoke(
            TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation)
            throws Exception {
        return route(invocation.modelId()).invoke(turnId, invocation, events, cancellation);
    }

    @Override
    public ModelInvocationResult invokeContinuing(
            TurnId turnId,
            ModelInvocation invocation,
            ProviderState state,
            ModelEventSink events,
            CancellationToken cancellation)
            throws Exception {
        ModelGateway gateway = route(invocation.modelId());
        if (!(gateway instanceof NativeConversationSupport nativeSupport)) {
            throw new IllegalStateException("model endpoint does not support opaque conversation state");
        }
        return nativeSupport.invokeContinuing(turnId, invocation, state, events, cancellation);
    }

    @Override
    public ProviderState restoreCoveredState(
            String modelId, ProviderState state, java.util.List<com.javaclaw.runtime.ModelMessage> coveredMessages) {
        ModelGateway gateway = route(modelId);
        if (!(gateway instanceof NativeConversationSupport support)) {
            throw new IllegalStateException("Provider 不支持旧状态恢复");
        }
        return support.restoreCoveredState(modelId, state, coveredMessages);
    }

    @Override
    public NativeCompactionResult compact(NativeCompactionRequest request, CancellationToken cancellation)
            throws Exception {
        ModelGateway gateway = route(request.modelId());
        if (!(gateway instanceof NativeCompactionSupport nativeSupport)) {
            throw new IllegalStateException("model endpoint does not support native compaction");
        }
        return nativeSupport.compact(request, cancellation);
    }

    private ModelGateway route(String modelId) {
        ModelGateway gateway = routes.get(modelId);
        if (gateway == null) {
            throw new IllegalArgumentException("unknown model endpoint: " + modelId);
        }
        return gateway;
    }

    @Override
    public void close() throws Exception {
        Set<ModelGateway> unique = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        unique.addAll(routes.values());
        List<Exception> failures = new ArrayList<>();
        for (ModelGateway gateway : unique) {
            if (gateway instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception failure) {
                    failures.add(failure);
                }
            }
        }
        if (!failures.isEmpty()) {
            Exception first = failures.removeFirst();
            failures.forEach(first::addSuppressed);
            throw first;
        }
    }

    /** App Server bootstrap 使用的显式路由组合器。 */
    public static final class Builder {
        private final Map<String, ModelGateway> routes = new LinkedHashMap<>();

        private Builder() {}

        /**
         * 注册端点与其实现。
         *
         * @param endpointId 稳定端点标识
         * @param gateway 适配器
         * @return 当前 Builder
         */
        public Builder register(String endpointId, ModelGateway gateway) {
            String id = Objects.requireNonNull(endpointId, "endpointId").strip();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("endpointId must not be blank");
            }
            Objects.requireNonNull(gateway, "gateway").capabilities(id);
            if (routes.put(id, gateway) != null) {
                throw new IllegalArgumentException("duplicate model endpoint: " + id);
            }
            return this;
        }

        /**
         * 冻结路由表。
         *
         * @return 路由器
         */
        public ModelGatewayRouter build() {
            if (routes.isEmpty()) {
                throw new IllegalStateException("at least one model endpoint is required");
            }
            return new ModelGatewayRouter(routes);
        }
    }
}
