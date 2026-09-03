package com.javaclaw.server.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.server.model.EmbeddingAdapterFactory;
import com.javaclaw.server.persistence.EmbeddingBindingService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.vault.VaultRuntimeGate;

/**
 * 从 H2 安装级精确绑定原子切换 Embedding Adapter。
 *
 * <p>Vault 变化时先通过共享门闩阻断新 lease，再退役旧 Generation 并重建。lease 获取前后必须处于同一开放 epoch，避免凭据失效期间继续获得旧 Adapter。
 */
public final class ProviderEmbeddingRegistry implements EmbeddingPort, AutoCloseable {
    private final ProviderService providers;
    private final EmbeddingBindingService bindings;
    private final EmbeddingAdapterFactory adapters;
    private final VaultRuntimeGate runtimeGate;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile Generation current = Generation.unavailable();
    private boolean closed;

    /**
     * 创建并加载与 Vault 状态线性化的安装级 Embedding 路由。
     *
     * @param providers Provider 历史服务
     * @param bindings 安装级精确绑定服务
     * @param adapters 精确版本 Adapter 构造器
     * @param runtimeGate Vault 变化期间阻止新 lease 的共享门闩
     */
    public ProviderEmbeddingRegistry(
            ProviderService providers,
            EmbeddingBindingService bindings,
            EmbeddingAdapterFactory adapters,
            VaultRuntimeGate runtimeGate) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.runtimeGate = Objects.requireNonNull(runtimeGate, "runtimeGate");
        reload();
        providers.participate(this::prepareProvider);
        bindings.participate(this::prepareBinding);
    }

    /** 从 H2 重建并原子切换安装级 Embedding 路由。 */
    public void reload() {
        refreshLock.lock();
        try {
            if (closed) {
                return;
            }
            Generation next = build(providers.listAllVersions(), bindings.find());
            Generation previous = current;
            current = next;
            previous.retire();
        } finally {
            refreshLock.unlock();
        }
    }

    /** 立即退役已复制 Vault 凭据的 Adapter；重建失败时保持不可用状态。 */
    public void invalidate() {
        refreshLock.lock();
        try {
            if (closed) {
                return;
            }
            Generation previous = current;
            current = Generation.unavailable();
            previous.retire();
        } finally {
            refreshLock.unlock();
        }
    }

    @Override
    public EmbeddingBatch embed(List<String> texts, EmbeddingPurpose purpose, CancellationToken cancellation)
            throws Exception {
        try (Lease lease = acquireCurrent()) {
            return lease.generation().port().embed(texts, purpose, cancellation);
        }
    }

    /** 停止刷新，并在在途调用释放后关闭当前 Adapter。 */
    @Override
    public void close() {
        refreshLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            Generation previous = current;
            current = Generation.unavailable();
            previous.retire();
        } finally {
            refreshLock.unlock();
        }
    }

    private ProviderService.PreparedProviderChange prepareProvider(ProviderEndpoint candidate) {
        refreshLock.lock();
        try {
            requireOpen();
            return new PreparedGeneration(build(historyWith(candidate), bindings.find()));
        } catch (RuntimeException failure) {
            refreshLock.unlock();
            throw failure;
        }
    }

    private EmbeddingBindingService.PreparedBindingChange prepareBinding(EmbeddingBinding candidate) {
        refreshLock.lock();
        try {
            requireOpen();
            return new PreparedGeneration(build(providers.listAllVersions(), Optional.of(candidate)));
        } catch (RuntimeException failure) {
            refreshLock.unlock();
            throw failure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Provider Embedding Registry is closed");
        }
    }

    private List<ProviderEndpoint> historyWith(ProviderEndpoint candidate) {
        List<ProviderEndpoint> history = new ArrayList<>(providers.listAllVersions());
        history.removeIf(
                endpoint -> endpoint.id().equals(candidate.id()) && endpoint.revision() == candidate.revision());
        history.add(candidate);
        return List.copyOf(history);
    }

    private Generation build(List<ProviderEndpoint> history, Optional<EmbeddingBinding> selected) {
        if (selected.isEmpty()) {
            return Generation.unavailable();
        }
        ProviderRef reference = selected.orElseThrow().provider();
        Map<String, ProviderEndpoint> latest = new HashMap<>();
        history.forEach(endpoint -> latest.merge(
                endpoint.id(), endpoint, (left, right) -> left.revision() > right.revision() ? left : right));
        ProviderEndpoint currentEndpoint = latest.get(reference.endpointId());
        if (currentEndpoint == null || currentEndpoint.lifecycle() != ProviderLifecycle.ACTIVE) {
            return Generation.unavailable();
        }
        Optional<ProviderEndpoint> exact = history.stream()
                .filter(endpoint -> endpoint.id().equals(reference.endpointId()))
                .filter(endpoint -> endpoint.revision() == reference.endpointRevision())
                .findFirst();
        if (exact.isEmpty() || exact.orElseThrow().lifecycle() != ProviderLifecycle.ACTIVE) {
            return Generation.unavailable();
        }
        boolean embeddingModel = exact.orElseThrow().spec().models().stream()
                .anyMatch(model ->
                        model.modelId().equals(reference.model()) && model.supports(ProviderModelPurpose.EMBEDDING));
        if (!embeddingModel) {
            return Generation.unavailable();
        }
        return new Generation(adapters.create(exact.orElseThrow(), reference));
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

    private static final class Generation {
        private final EmbeddingPort port;
        private final AtomicInteger leases = new AtomicInteger();
        private final AtomicBoolean retired = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Generation(EmbeddingPort port) {
            this.port = Objects.requireNonNull(port, "port");
        }

        static Generation unavailable() {
            return new Generation(EmbeddingPort.unavailable());
        }

        Lease tryAcquire() {
            leases.incrementAndGet();
            if (retired.get()) {
                release();
                return null;
            }
            return new Lease(this);
        }

        EmbeddingPort port() {
            return port;
        }

        void retire() {
            retired.set(true);
            closeIfUnused();
        }

        void release() {
            int remaining = leases.decrementAndGet();
            if (remaining < 0) {
                throw new IllegalStateException("Embedding registry lease underflow");
            }
            closeIfUnused();
        }

        private void closeIfUnused() {
            if (retired.get() && leases.get() == 0 && closed.compareAndSet(false, true)) {
                close(port);
            }
        }

        private static void close(EmbeddingPort port) {
            if (port instanceof AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Exception failure) {
                    System.getLogger(ProviderEmbeddingRegistry.class.getName())
                            .log(System.Logger.Level.WARNING, failure);
                }
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
                throw new IllegalStateException("Embedding registry lease is closed");
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

    private final class PreparedGeneration
            implements ProviderService.PreparedProviderChange, EmbeddingBindingService.PreparedBindingChange {
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
                throw new IllegalStateException("Prepared Embedding generation is no longer pending");
            }
            return next;
        }
    }
}
