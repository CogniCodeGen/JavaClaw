package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderCapabilities;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.server.model.EmbeddingAdapterFactory;

/**
 * 使用临时精确 Adapter 执行一次向量连通性验证。
 *
 * <p>隐私不变量：固定输入和向量都只存在于调用栈；结果只保留用途、耗时和脱敏终态。资源在成功或失败后关闭，且调用不读取或修改全局 {@code EmbeddingBinding}。
 */
final class ProviderEmbeddingVerificationHarness implements AutoCloseable {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);
    private static final String VERIFICATION_TEXT = "JavaClaw embedding connectivity check";
    private static final ProviderCapabilities CAPABILITIES = new ProviderCapabilities(
            Set.of(ProviderModelPurpose.EMBEDDING), false, false, false, false, false, false, false);

    private final EmbeddingAdapterFactory adapters;
    private final Clock clock;
    private final Duration maximumTimeout;
    private final ExecutorService executor;

    ProviderEmbeddingVerificationHarness(EmbeddingAdapterFactory adapters, Clock clock) {
        this(adapters, clock, DEFAULT_TIMEOUT, Executors.newVirtualThreadPerTaskExecutor());
    }

    ProviderEmbeddingVerificationHarness(
            EmbeddingAdapterFactory adapters, Clock clock, Duration maximumTimeout, ExecutorService executor) {
        this.adapters = java.util.Objects.requireNonNull(adapters, "adapters");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.maximumTimeout = requirePositive(maximumTimeout);
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    ProviderVerificationResult execute(
            ProviderEndpoint endpoint, ProviderRef provider, CancellationToken cancellation) {
        ProviderEndpoint checkedEndpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
        ProviderRef checkedProvider = java.util.Objects.requireNonNull(provider, "provider");
        CancellationToken checkedCancellation = java.util.Objects.requireNonNull(cancellation, "cancellation");
        if (checkedCancellation.isCancelled()) {
            return cancelled(checkedProvider, 0);
        }
        Duration timeout = minimum(maximumTimeout, checkedEndpoint.spec().timeout());
        long startedNanos = System.nanoTime();
        CancellationSource localCancellation = new CancellationSource();
        CancellationToken combined = new CombinedCancellationToken(checkedCancellation, localCancellation);
        Future<EmbeddingBatch> task = executor.submit(() -> embed(checkedEndpoint, checkedProvider, combined));
        return await(task, checkedEndpoint, checkedProvider, combined, localCancellation, startedNanos, timeout);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private EmbeddingBatch embed(ProviderEndpoint endpoint, ProviderRef provider, CancellationToken cancellation)
            throws Exception {
        EmbeddingPort port = adapters.create(endpoint, provider);
        try {
            return port.embed(List.of(VERIFICATION_TEXT), EmbeddingPurpose.QUERY, cancellation);
        } finally {
            if (port instanceof AutoCloseable resource) {
                resource.close();
            }
        }
    }

    private ProviderVerificationResult await(
            Future<EmbeddingBatch> task,
            ProviderEndpoint endpoint,
            ProviderRef provider,
            CancellationToken cancellation,
            CancellationSource localCancellation,
            long startedNanos,
            Duration timeout) {
        long deadline = Math.addExact(startedNanos, timeout.toNanos());
        while (true) {
            if (cancellation.isCancelled()) {
                localCancellation.cancel("Provider embedding verification cancelled");
                task.cancel(true);
                return cancelled(provider, elapsedMillis(startedNanos));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return timedOut(task, provider, localCancellation, startedNanos);
            }
            try {
                EmbeddingBatch result = task.get(Math.min(remaining, POLL_INTERVAL.toNanos()), TimeUnit.NANOSECONDS);
                validate(endpoint, provider, result);
                return result(
                        provider, ProviderVerificationState.SUCCEEDED, elapsedMillis(startedNanos), Optional.empty());
            } catch (TimeoutException ignored) {
                // 周期性检查外部取消和平台时限。
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                localCancellation.cancel("Provider embedding verification interrupted");
                task.cancel(true);
                return cancelled(provider, elapsedMillis(startedNanos));
            } catch (ExecutionException | RuntimeException failure) {
                return result(
                        provider,
                        ProviderVerificationState.FAILED,
                        elapsedMillis(startedNanos),
                        Optional.of("EMBEDDING_VERIFICATION_FAILED"));
            }
        }
    }

    private ProviderVerificationResult timedOut(
            Future<EmbeddingBatch> task, ProviderRef provider, CancellationSource cancellation, long startedNanos) {
        cancellation.cancel("Provider embedding verification timeout");
        task.cancel(true);
        return result(
                provider,
                ProviderVerificationState.TIMED_OUT,
                elapsedMillis(startedNanos),
                Optional.of("VERIFICATION_TIMED_OUT"));
    }

    private ProviderVerificationResult cancelled(ProviderRef provider, long latencyMillis) {
        return result(
                provider, ProviderVerificationState.CANCELLED, latencyMillis, Optional.of("VERIFICATION_CANCELLED"));
    }

    private ProviderVerificationResult result(
            ProviderRef provider, ProviderVerificationState state, long latencyMillis, Optional<String> errorCode) {
        return new ProviderVerificationResult(
                provider,
                ProviderModelPurpose.EMBEDDING,
                state,
                latencyMillis,
                Optional.empty(),
                CAPABILITIES,
                errorCode,
                clock.instant());
    }

    private static void validate(ProviderEndpoint endpoint, ProviderRef provider, EmbeddingBatch result) {
        if (result.vectors().size() != 1 || result.vectors().getFirst().values().size() != result.dimensions()) {
            throw new IllegalStateException("Embedding verification requires exactly one valid vector");
        }
        ProviderModelSpec model = endpoint.spec().models().stream()
                .filter(candidate -> candidate.modelId().equals(provider.model()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Embedding model disappeared during verification"));
        model.embeddingDimensions().ifPresent(expected -> {
            if (expected != result.dimensions()) {
                throw new IllegalStateException("Embedding dimensions differ from Provider configuration");
            }
        });
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static Duration requirePositive(Duration value) {
        Duration checked = java.util.Objects.requireNonNull(value, "maximumTimeout");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException("maximumTimeout must be positive");
        }
        return checked;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }
}
