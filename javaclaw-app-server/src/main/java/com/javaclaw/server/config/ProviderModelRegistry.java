package com.javaclaw.server.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
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
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.vault.VaultRuntimeGate;

/**
 * 从 H2 Provider 历史建立可原子替换的精确 ModelGateway 路由。
 *
 * <p>实现说明：每次刷新先完整构造新 Generation，再一次交换引用；旧 Generation 等在途调用释放后关闭。Provider 的最新版本一旦 DISABLED/ARCHIVED，该 Provider
 * 的全部历史路由立即从新 Generation 移除，形成实时 kill switch。
 *
 * <p>Vault 变化时先通过共享门闩阻断新 lease，再退役旧 Generation 并重建。lease 获取前后必须处于同一开放 epoch，避免凭据失效期间继续获得旧 Adapter。
 */
public final class ProviderModelRegistry
        implements ModelGateway, NativeConversationSupport, NativeCompactionSupport, AutoCloseable {
    private final ProviderService providers;
    private final AdapterFactory adapters;
    private final VaultRuntimeGate runtimeGate;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile Generation current = Generation.empty();
    private boolean closed;

    /**
     * 创建并加载与 Vault 状态线性化的 H2 Provider 路由。
     *
     * @param providers Provider 历史服务
     * @param adapters 精确 Adapter 构造边界
     * @param runtimeGate Vault 变化期间阻止新 lease 的共享门闩
     */
    public ProviderModelRegistry(ProviderService providers, AdapterFactory adapters, VaultRuntimeGate runtimeGate) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.runtimeGate = Objects.requireNonNull(runtimeGate, "runtimeGate");
        reload();
        providers.participate(this::prepare);
    }

    /** 从 H2 重新构造并原子切换路由。 */
    public void reload() {
        refreshLock.lock();
        try {
            if (closed) {
                return;
            }
            Generation next = build(providers.listAllVersions());
            Generation previous = current;
            current = next;
            previous.retire();
        } finally {
            refreshLock.unlock();
        }
    }

    /** 立即退役所有已复制 Vault 凭据的 Adapter；重建失败时保持无路由状态。 */
    public void invalidate() {
        refreshLock.lock();
        try {
            if (closed) {
                return;
            }
            Generation previous = current;
            current = Generation.empty();
            previous.retire();
        } finally {
            refreshLock.unlock();
        }
    }

    @Override
    public ModelCapabilities capabilities(String modelId) {
        try (Lease lease = acquireCurrent()) {
            return lease.generation().route(modelId).capabilities(modelId);
        }
    }

    @Override
    public ModelInvocationResult invoke(
            TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation)
            throws Exception {
        try (Lease lease = acquireCurrent()) {
            return lease.generation().route(invocation.modelId()).invoke(turnId, invocation, events, cancellation);
        }
    }

    @Override
    public ModelInvocationResult invokeContinuing(
            TurnId turnId,
            ModelInvocation invocation,
            ProviderState state,
            ModelEventSink events,
            CancellationToken cancellation)
            throws Exception {
        try (Lease lease = acquireCurrent()) {
            ModelGateway gateway = lease.generation().route(invocation.modelId());
            if (!(gateway instanceof NativeConversationSupport support)) {
                throw new IllegalStateException("Provider does not support opaque conversation state");
            }
            return support.invokeContinuing(turnId, invocation, state, events, cancellation);
        }
    }

    @Override
    public ProviderState restoreCoveredState(
            String modelId, ProviderState state, java.util.List<com.javaclaw.runtime.ModelMessage> coveredMessages) {
        try (Lease lease = acquireCurrent()) {
            ModelGateway gateway = lease.generation().route(modelId);
            if (!(gateway instanceof NativeConversationSupport support)) {
                throw new IllegalStateException("Provider 不支持旧状态恢复");
            }
            return support.restoreCoveredState(modelId, state, coveredMessages);
        }
    }

    @Override
    public NativeCompactionResult compact(NativeCompactionRequest request, CancellationToken cancellation)
            throws Exception {
        try (Lease lease = acquireCurrent()) {
            ModelGateway gateway = lease.generation().route(request.modelId());
            if (!(gateway instanceof NativeCompactionSupport support)) {
                throw new IllegalStateException("Provider does not support native compaction");
            }
            return support.compact(request, cancellation);
        }
    }

    /** 停止刷新并在在途调用释放后关闭所有 Adapter。 */
    @Override
    public void close() {
        refreshLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            Generation previous = current;
            current = Generation.empty();
            previous.retire();
        } finally {
            refreshLock.unlock();
        }
    }

    private ProviderService.PreparedProviderChange prepare(ProviderEndpoint candidate) {
        refreshLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Provider Model Registry is closed");
            }
            return new PreparedGeneration(build(historyWith(candidate)));
        } catch (RuntimeException failure) {
            refreshLock.unlock();
            throw failure;
        }
    }

    private List<ProviderEndpoint> historyWith(ProviderEndpoint candidate) {
        List<ProviderEndpoint> history = new ArrayList<>(providers.listAllVersions());
        history.removeIf(
                endpoint -> endpoint.id().equals(candidate.id()) && endpoint.revision() == candidate.revision());
        history.add(candidate);
        return List.copyOf(history);
    }

    private Generation build(List<ProviderEndpoint> history) {
        Map<String, ProviderEndpoint> latest = new HashMap<>();
        for (ProviderEndpoint endpoint : history) {
            latest.put(endpoint.id(), endpoint);
        }
        Map<String, ModelGateway> routes = new HashMap<>();
        try {
            for (ProviderEndpoint endpoint : history) {
                ProviderEndpoint currentEndpoint = latest.get(endpoint.id());
                if (currentEndpoint.lifecycle() != ProviderLifecycle.ACTIVE
                        || endpoint.lifecycle() != ProviderLifecycle.ACTIVE) {
                    continue;
                }
                for (var model : endpoint.spec().models()) {
                    if (!model.supports(ProviderModelPurpose.CHAT)) {
                        continue;
                    }
                    ProviderRef reference = new ProviderRef(endpoint.id(), endpoint.revision(), model.modelId());
                    routes.put(reference.routeKey(), adapters.create(endpoint, reference));
                }
            }
            return new Generation(routes);
        } catch (RuntimeException failure) {
            closeUnique(routes.values());
            throw failure;
        }
    }

    private Lease acquireCurrent() {
        while (true) {
            long gateStamp = runtimeGate.requireOpenStamp();
            Generation observed = current;
            Lease lease = observed.tryAcquire();
            if (lease != null && runtimeGate.remainsOpen(gateStamp)) {
                return lease;
            }
            if (lease != null) {
                lease.close();
            }
        }
    }

    /** ProviderEndpoint 到可关闭 ModelGateway 的创建边界。 */
    @FunctionalInterface
    public interface AdapterFactory {
        /**
         * 创建精确版本 Adapter。
         *
         * @param endpoint Provider 版本
         * @param reference 模型引用
         * @return 独立或可共享网关
         */
        ModelGateway create(ProviderEndpoint endpoint, ProviderRef reference);
    }

    private static final class Generation {
        private final Map<String, ModelGateway> routes;
        private final AtomicInteger leases = new AtomicInteger();
        private final AtomicBoolean retired = new AtomicBoolean();
        private final AtomicBoolean resourcesClosed = new AtomicBoolean();

        private Generation(Map<String, ModelGateway> routes) {
            this.routes = Map.copyOf(routes);
        }

        static Generation empty() {
            return new Generation(Map.of());
        }

        Lease tryAcquire() {
            leases.incrementAndGet();
            if (retired.get()) {
                release();
                return null;
            }
            return new Lease(this);
        }

        ModelGateway route(String modelId) {
            ModelGateway gateway = routes.get(modelId);
            if (gateway == null) {
                throw new IllegalArgumentException("Provider model route is unavailable: " + modelId);
            }
            return gateway;
        }

        void retire() {
            retired.set(true);
            closeIfUnused();
        }

        void release() {
            int remaining = leases.decrementAndGet();
            if (remaining < 0) {
                throw new IllegalStateException("Provider registry lease underflow");
            }
            closeIfUnused();
        }

        private void closeIfUnused() {
            if (retired.get() && leases.get() == 0 && resourcesClosed.compareAndSet(false, true)) {
                closeUnique(routes.values());
            }
        }
    }

    private static final class Lease implements AutoCloseable {
        private Generation generation;

        private Lease(Generation generation) {
            this.generation = generation;
        }

        Generation generation() {
            if (generation == null) {
                throw new IllegalStateException("Provider registry lease is closed");
            }
            return generation;
        }

        @Override
        public void close() {
            Generation owned = generation;
            if (owned != null) {
                generation = null;
                owned.release();
            }
        }
    }

    private final class PreparedGeneration implements ProviderService.PreparedProviderChange {
        private Generation next;

        private PreparedGeneration(Generation next) {
            this.next = Objects.requireNonNull(next, "next");
        }

        @Override
        public void activate() {
            Generation prepared = requirePending();
            Generation previous = current;
            current = prepared;
            next = null;
            try {
                previous.retire();
            } finally {
                refreshLock.unlock();
            }
        }

        @Override
        public void close() {
            Generation prepared = next;
            if (prepared == null) {
                return;
            }
            next = null;
            try {
                prepared.retire();
            } finally {
                refreshLock.unlock();
            }
        }

        private Generation requirePending() {
            if (next == null) {
                throw new IllegalStateException("Prepared Provider generation is no longer pending");
            }
            return next;
        }
    }

    private static void closeUnique(java.util.Collection<ModelGateway> gateways) {
        Set<ModelGateway> unique = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        unique.addAll(gateways);
        List<Exception> failures = new ArrayList<>();
        for (ModelGateway gateway : unique) {
            if (gateway instanceof AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Exception failure) {
                    failures.add(failure);
                }
            }
        }
        if (!failures.isEmpty()) {
            // 关闭发生在配置提交之后，不能回滚新 Generation；保持错误可诊断但不破坏实时切换。
            failures.forEach(failure ->
                    System.getLogger(ProviderModelRegistry.class.getName()).log(System.Logger.Level.WARNING, failure));
        }
    }
}
