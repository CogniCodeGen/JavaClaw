package com.javaclaw.server.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRole;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.server.persistence.ProviderService;

/** 从 H2 Provider 最新版本原子切换全局 Embedding endpoint。 */
public final class ProviderEmbeddingRegistry implements EmbeddingPort, AutoCloseable {
    private final ProviderService providers;
    private final AdapterFactory adapters;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile Generation current = Generation.unavailable();
    private boolean closed;

    /**
     * 创建并加载 Embedding 路由。
     *
     * @param providers Provider 历史服务
     * @param adapters 精确版本 Adapter 构造器
     */
    public ProviderEmbeddingRegistry(ProviderService providers, AdapterFactory adapters) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        reload();
        providers.participate(this::prepare);
    }

    /** 从 H2 重新构造并原子切换 Embedding endpoint。 */
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

    private ProviderService.PreparedProviderChange prepare(ProviderEndpoint candidate) {
        refreshLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Provider Embedding Registry is closed");
            }
            validateCandidate(candidate);
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

    private void validateCandidate(ProviderEndpoint candidate) {
        if (candidate.lifecycle() != ProviderLifecycle.ACTIVE
                || !candidate.spec().roles().contains(ProviderRole.EMBEDDING)) {
            return;
        }
        List<EmbeddingPort> probes = new ArrayList<>();
        try {
            for (String model : candidate.spec().models()) {
                probes.add(adapters.create(candidate, model));
            }
        } catch (RuntimeException failure) {
            try {
                closeProbes(probes);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        closeProbes(probes);
    }

    private static void closeProbes(List<EmbeddingPort> probes) {
        RuntimeException first = null;
        for (int index = probes.size() - 1; index >= 0; index--) {
            if (probes.get(index) instanceof AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Exception failure) {
                    RuntimeException wrapped =
                            new IllegalStateException("Embedding candidate could not be released", failure);
                    if (first == null) {
                        first = wrapped;
                    } else {
                        first.addSuppressed(wrapped);
                    }
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private Generation build(List<ProviderEndpoint> history) {
        Map<String, ProviderEndpoint> latest = new HashMap<>();
        history.forEach(endpoint -> latest.put(endpoint.id(), endpoint));
        List<Candidate> candidates = latest.values().stream()
                .filter(endpoint -> endpoint.lifecycle() == ProviderLifecycle.ACTIVE)
                .filter(endpoint -> endpoint.spec().roles().contains(ProviderRole.EMBEDDING))
                .flatMap(endpoint -> endpoint.spec().models().stream().map(model -> new Candidate(endpoint, model)))
                .sorted(Comparator.comparing(Candidate::preferred)
                        .reversed()
                        .thenComparing(candidate -> candidate.endpoint().id())
                        .thenComparing(Candidate::model))
                .toList();
        if (candidates.isEmpty()) {
            return Generation.unavailable();
        }
        Candidate selected = candidates.getFirst();
        return new Generation(adapters.create(selected.endpoint(), selected.model()));
    }

    private Lease acquireCurrent() {
        while (true) {
            Generation observed = current;
            Lease lease = observed.tryAcquire();
            if (lease != null) {
                return lease;
            }
        }
    }

    /** Provider 版本到 EmbeddingPort 的构造边界。 */
    @FunctionalInterface
    public interface AdapterFactory {
        /**
         * 创建精确 Adapter。
         *
         * @param endpoint Provider 最新版本
         * @param model Provider 模型
         * @return 可关闭或无状态 EmbeddingPort
         */
        EmbeddingPort create(ProviderEndpoint endpoint, String model);
    }

    private record Candidate(ProviderEndpoint endpoint, String model) {
        private Candidate {
            Objects.requireNonNull(endpoint, "endpoint");
            model = Objects.requireNonNull(model, "model");
        }

        boolean preferred() {
            return Boolean.parseBoolean(endpoint.spec().options().getOrDefault("defaultEmbedding", "false"));
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
                throw new IllegalStateException("Prepared Embedding generation is no longer pending");
            }
            return next;
        }
    }
}
