package com.javaclaw.memory.embed;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.spi.EmbeddingModelProvider;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 工作区级唯一嵌入入口：按交互/后台场景执行超时、重试、熔断与健康状态广播。
 * 所有失败返回 {@code null}，上层可安全降级。
 */
public class EmbeddingGateway {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingGateway.class);
    private static final int[] OPEN_SECONDS = {30, 60, 120, 240, 300};

    @FunctionalInterface
    interface EmbeddingInvoker {
        double[] embed(String text, Duration timeout) throws Exception;
    }

    private final EmbeddingInvoker invoker;
    private final int dimensions;
    private final Clock clock;
    private final TaskScope tasks;
    private final AtomicInteger failures = new AtomicInteger();
    /** 配置/模型创建或显式探测失败后，必须由一次成功调用才能恢复。 */
    private final AtomicBoolean hardUnavailable = new AtomicBoolean();
    /** 保证工作区启动时的后台健康探测最多调度一次。 */
    private final AtomicBoolean initialProbeStarted = new AtomicBoolean();
    private final CopyOnWriteArrayList<Consumer<EmbeddingHealthSnapshot>> listeners =
            new CopyOnWriteArrayList<>();
    private volatile EmbeddingHealthSnapshot health;

    public EmbeddingGateway(
            EmbeddingModelProvider provider, TaskScope tasks, AgentConfig config) {
        Objects.requireNonNull(provider, "provider");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(config, "config");
        this.clock = Clock.systemUTC();
        this.dimensions = config.getRagEmbeddingDimensions();
        EmbeddingHealthStatus initial;
        String error = provider.initializationError();
        if (!provider.configured()) {
            initial = EmbeddingHealthStatus.UNCONFIGURED;
        } else if (error != null) {
            initial = EmbeddingHealthStatus.UNAVAILABLE;
        } else {
            initial = EmbeddingHealthStatus.CHECKING;
        }
        this.invoker = !provider.configured() || error != null ? null : (text, timeout) -> {
            var handle = tasks.submit(TaskSpec.io("spring-ai-embedding")
                    .withTimeout(timeout), context -> {
                context.cancellation().throwIfCancellationRequested();
                return provider.embed(text, timeout);
            });
            try {
                return handle.completion().get(
                        timeout.toMillis() + 250, java.util.concurrent.TimeUnit.MILLISECONDS);
            } finally {
                if (!handle.state().isTerminal()) handle.cancel();
            }
        };
        this.health = new EmbeddingHealthSnapshot(
                initial, error, error == null ? 0 : 1, null, Instant.now(clock));
        this.hardUnavailable.set(error != null);
    }

    /** 测试注入入口：不创建真实模型，仍完整经过超时、重试和熔断逻辑。 */
    EmbeddingGateway(
            int dimensions,
            EmbeddingHealthStatus initial,
            EmbeddingInvoker invoker,
            TaskScope tasks) {
        this(dimensions, initial, invoker, Clock.systemUTC(), tasks);
    }

    EmbeddingGateway(int dimensions, EmbeddingHealthStatus initial,
                     EmbeddingInvoker invoker, Clock clock, TaskScope tasks) {
        this.dimensions = dimensions;
        this.invoker = invoker;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.health = new EmbeddingHealthSnapshot(
                initial, null, 0, null, Instant.now(clock));
    }

    public int dimensions() {
        return dimensions;
    }

    public String lastError() {
        return health.lastError();
    }

    public boolean isModelReady() {
        return invoker != null;
    }

    public EmbeddingHealthSnapshot healthSnapshot() {
        return health;
    }

    public AutoCloseable addHealthListener(Consumer<EmbeddingHealthSnapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        listener.accept(health);
        return () -> listeners.remove(listener);
    }

    /**
     * 在后台执行一次启动健康探测，使已配置模型的初始 {@code CHECKING} 状态
     * 必然收敛为健康或不可用。未配置、模型创建失败及重复调用均为空操作。
     */
    public void startInitialProbe() {
        if (invoker == null || health.status() != EmbeddingHealthStatus.CHECKING
                || !initialProbeStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            tasks.submit(TaskSpec.io("embedding-initial-health-probe")
                    .withTimeout(Duration.ofSeconds(5)), context -> {
                context.cancellation().throwIfCancellationRequested();
                if (health.status() == EmbeddingHealthStatus.CHECKING) probe();
                return null;
            });
        } catch (RuntimeException failure) {
            initialProbeStarted.set(false);
            log.warn("嵌入初始健康探测调度失败: {}", failure.getMessage());
        }
    }

    /** 兼容现有通知端口：仅在健康状态实际转入降级/不可用时触发。 */
    public void setOnDegraded(Consumer<String> callback) {
        if (callback == null) return;
        var previous = new java.util.concurrent.atomic.AtomicReference<>(health.status());
        addHealthListener(snapshot -> {
            EmbeddingHealthStatus old = previous.getAndSet(snapshot.status());
            if (old != snapshot.status()
                    && (snapshot.status() == EmbeddingHealthStatus.DEGRADED
                    || snapshot.status() == EmbeddingHealthStatus.UNAVAILABLE)
                    && snapshot.lastError() != null) {
                callback.accept(snapshot.lastError());
            }
        });
    }

    public float[] embed(String text, EmbeddingPurpose purpose) {
        if (text == null || text.isBlank()) return null;
        if (invoker == null) return unavailableWithoutModel();

        EmbeddingHealthSnapshot current = health;
        Instant now = Instant.now(clock);
        if (current.circuitOpenUntil() != null && now.isBefore(current.circuitOpenUntil())) {
            return null;
        }

        Duration timeout = purpose == EmbeddingPurpose.INTERACTIVE_RECALL
                ? Duration.ofSeconds(1) : Duration.ofSeconds(10);
        int attempts = purpose == EmbeddingPurpose.BACKGROUND_INDEX ? 2 : 1;
        if (current.status() != EmbeddingHealthStatus.HEALTHY
                && current.status() != EmbeddingHealthStatus.DEGRADED) {
            transition(EmbeddingHealthStatus.CHECKING, current.lastError(),
                    failures.get(), null);
        }
        Throwable last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                float[] result = invoke(text, timeout);
                failures.set(0);
                hardUnavailable.set(false);
                transition(EmbeddingHealthStatus.HEALTHY, null, 0, null);
                return result;
            } catch (Throwable e) {
                // 生命周期取消不是端点故障：立即停止后台重试，也不要污染健康度/熔断状态。
                if (Thread.currentThread().isInterrupted()) {
                    log.debug("嵌入调用被生命周期取消");
                    return null;
                }
                last = e;
            }
        }
        recordFailure(last);
        return null;
    }

    /** 旧调用方默认视为后台写入；新代码应显式传 purpose。 */
    public float[] embed(String text) {
        return embed(text, EmbeddingPurpose.BACKGROUND_INDEX);
    }

    /** 主动探测：3 秒超时、绕过熔断器；成功会立即恢复健康。 */
    public EmbeddingHealthSnapshot probe() {
        if (invoker == null) {
            unavailableWithoutModel();
            return health;
        }
        transition(EmbeddingHealthStatus.CHECKING, health.lastError(), failures.get(), null);
        try {
            invoke("JavaClaw embedding health probe", Duration.ofSeconds(3));
            failures.set(0);
            hardUnavailable.set(false);
            transition(EmbeddingHealthStatus.HEALTHY, null, 0, null);
        } catch (Throwable e) {
            recordProbeFailure(e);
        }
        return health;
    }

    private float[] unavailableWithoutModel() {
        if (health.status() == EmbeddingHealthStatus.UNCONFIGURED) return null;
        String error = health.lastError() == null
                ? "嵌入模型未创建，请检查 rag.embedding.* 配置" : health.lastError();
        hardUnavailable.set(true);
        transition(EmbeddingHealthStatus.UNAVAILABLE, error,
                Math.max(1, failures.get()), null);
        return null;
    }

    private float[] invoke(String text, Duration timeout) {
        double[] values;
        try {
            values = invoker.embed(text, timeout);
        } catch (TimeoutException timeoutFailure) {
            throw new IllegalStateException("嵌入调用超时（" + timeout.toSeconds() + " 秒）",
                    timeoutFailure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("嵌入调用被中断", interrupted);
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception failure) {
            throw new IllegalStateException("嵌入调用失败", failure);
        }
        if (values == null || values.length == 0) {
            throw new IllegalStateException("嵌入服务返回空结果");
        }
        if (values.length != dimensions) {
            throw new IllegalStateException(
                    "嵌入向量维度不匹配：实际 " + values.length + "，配置 " + dimensions);
        }
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (float) values[i];
        return result;
    }

    private void recordFailure(Throwable failure) {
        int count = failures.incrementAndGet();
        int seconds = OPEN_SECONDS[Math.min(count - 1, OPEN_SECONDS.length - 1)];
        String error = describe(failure);
        Instant until = Instant.now(clock).plusSeconds(seconds);
        log.warn("嵌入调用失败，熔断 {} 秒（连续失败 {} 次）: {}", seconds, count, error);
        EmbeddingHealthStatus status = count < 3 && !hardUnavailable.get()
                ? EmbeddingHealthStatus.DEGRADED : EmbeddingHealthStatus.UNAVAILABLE;
        transition(status, error, count, until);
    }

    private void recordProbeFailure(Throwable failure) {
        int count = failures.incrementAndGet();
        hardUnavailable.set(true);
        int seconds = OPEN_SECONDS[Math.min(count - 1, OPEN_SECONDS.length - 1)];
        String error = describe(failure);
        Instant until = Instant.now(clock).plusSeconds(seconds);
        log.warn("嵌入主动探测失败，立即标记不可用并熔断 {} 秒: {}", seconds, error);
        transition(EmbeddingHealthStatus.UNAVAILABLE, error, count, until);
    }

    private synchronized void transition(EmbeddingHealthStatus status, String error,
                                         int failureCount, Instant openUntil) {
        EmbeddingHealthSnapshot previous = health;
        EmbeddingHealthSnapshot next = new EmbeddingHealthSnapshot(
                status, error, failureCount, openUntil, Instant.now(clock));
        health = next;
        if (previous != null && previous.status() == status
                && Objects.equals(previous.lastError(), error)
                && Objects.equals(previous.circuitOpenUntil(), openUntil)) {
            return;
        }
        for (Consumer<EmbeddingHealthSnapshot> listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException e) {
                log.debug("嵌入健康监听器异常（忽略）: {}", e.getMessage());
            }
        }
    }

    private static String describe(Throwable error) {
        if (error == null) return "未知嵌入错误";
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            String part = current.getMessage();
            if (part == null || part.isBlank()) part = current.getClass().getSimpleName();
            if (text.indexOf(part) < 0) {
                if (!text.isEmpty()) text.append(" ← ");
                text.append(part.trim());
            }
        }
        return text.toString();
    }
}
