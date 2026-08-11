package com.javaclaw.platform.execution;

import org.slf4j.MDC;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JavaClaw 全局托管执行引擎。
 *
 * <p>I/O、浏览器和进程任务各使用独立虚拟线程执行器及并发配额；CPU 任务使用
 * 有界平台线程池。调度线程只负责触发超时。每个任务都会登记、传播提交时 MDC、
 * 绑定 {@link TaskContext}，并在终态自动移出注册表。</p>
 *
 * <p>实例线程安全。关闭后拒绝新任务，取消所有在途任务，并最多等待五秒释放执行器。
 * 任务必须同时检查取消信号和响应中断；平台不会使用危险的线程强停。</p>
 */
public final class ManagedTaskExecutor implements AutoCloseable {

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final ExecutorService ioExecutor;
    private final ThreadPoolExecutor cpuExecutor;
    private final ExecutorService browserExecutor;
    private final ExecutorService processExecutor;
    private final ScheduledExecutorService scheduler;
    private final Semaphore ioGate;
    private final Semaphore browserGate;
    private final Semaphore processGate;
    private final ConcurrentHashMap<String, Semaphore> browserSerialGates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Handle<?>> handles = new ConcurrentHashMap<>();
    private final Set<ScheduledTrigger> triggers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public ManagedTaskExecutor() {
        this(ExecutionLimits.defaults());
    }

    public ManagedTaskExecutor(ExecutionLimits limits) {
        ioExecutor = virtualExecutor("javaclaw-io-");
        browserExecutor = virtualExecutor("javaclaw-browser-");
        processExecutor = virtualExecutor("javaclaw-process-");
        cpuExecutor = new ThreadPoolExecutor(
                limits.cpuThreads(), limits.cpuThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(limits.cpuQueueCapacity()),
                platformFactory("javaclaw-cpu-"), new ThreadPoolExecutor.AbortPolicy());
        scheduler = Executors.newSingleThreadScheduledExecutor(
                platformFactory("javaclaw-scheduler-"));
        ioGate = new Semaphore(limits.ioConcurrency(), true);
        browserGate = new Semaphore(limits.browserConcurrency(), true);
        processGate = new Semaphore(limits.processConcurrency(), true);
    }

    public <T> TaskHandle<T> submit(TaskSpec spec, ManagedTask<T> task) {
        if (!accepting.get()) {
            throw new RejectedExecutionException("托管执行器已关闭");
        }
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Handle<T> handle = new Handle<>(UUID.randomUUID().toString(), spec);
        handles.put(handle.id(), handle);
        handle.completion().whenComplete((ignored, failure) -> {
            handles.remove(handle.id(), handle);
            handle.cancelTimeout();
        });

        Duration timeout = spec.timeout();
        if (!timeout.isZero()) {
            ScheduledFuture<?> timeoutFuture = scheduler.schedule(
                    handle::timeout, timeout.toNanos(), TimeUnit.NANOSECONDS);
            handle.bindTimeout(timeoutFuture);
        }

        try {
            Future<?> future = executorFor(spec.workload()).submit(
                    () -> runTask(handle, task, mdc));
            handle.bindExecution(future);
            return handle;
        } catch (RuntimeException failure) {
            handle.fail(failure);
            throw failure;
        }
    }

    public TaskHandle<Void> execute(TaskSpec spec, ManagedTask<Void> task) {
        return submit(spec, task);
    }

    /**
     * 延迟触发一次轻量动作。动作运行在唯一的调度平台线程上，必须只做任务派发。
     */
    public TriggerHandle scheduleTrigger(Duration delay, Runnable trigger) {
        Duration checkedDelay = requireNonNegative(delay, "触发延迟");
        return registerTrigger(checkedDelay, null, trigger);
    }

    /**
     * 按固定频率触发轻量动作。任务正文必须由动作显式派发到合适的托管执行器。
     */
    public TriggerHandle scheduleTriggerAtFixedRate(
            Duration initialDelay, Duration period, Runnable trigger) {
        Duration checkedInitialDelay = requireNonNegative(initialDelay, "首次触发延迟");
        Duration checkedPeriod = requirePositive(period, "触发周期");
        return registerTrigger(checkedInitialDelay, checkedPeriod, trigger);
    }

    /** 创建共享全局资源池、但拥有独立任务登记和关闭边界的作用域。 */
    public TaskScope openScope(String name, int maxConcurrentTasks) {
        if (!accepting.get()) {
            throw new RejectedExecutionException("托管执行器已关闭");
        }
        return new TaskScope(name, this, maxConcurrentTasks);
    }

    public int activeTaskCount() {
        return handles.size();
    }

    private TriggerHandle registerTrigger(Duration initialDelay, Duration period, Runnable action) {
        if (!accepting.get()) {
            throw new RejectedExecutionException("托管执行器已关闭");
        }
        if (action == null) {
            throw new NullPointerException("trigger");
        }
        ScheduledTrigger trigger = new ScheduledTrigger(period == null);
        triggers.add(trigger);
        Runnable guarded = () -> {
            if (trigger.isCancelled()) {
                return;
            }
            try {
                action.run();
            } finally {
                if (trigger.oneShot) {
                    trigger.finish();
                }
            }
        };
        try {
            ScheduledFuture<?> future = period == null
                    ? scheduler.schedule(guarded, initialDelay.toNanos(), TimeUnit.NANOSECONDS)
                    : scheduler.scheduleAtFixedRate(guarded, initialDelay.toNanos(),
                    period.toNanos(), TimeUnit.NANOSECONDS);
            trigger.bind(future);
            if (!accepting.get()) {
                trigger.cancel();
            }
            return trigger;
        } catch (RuntimeException failure) {
            triggers.remove(trigger);
            throw failure;
        }
    }

    private static Duration requireNonNegative(Duration value, String label) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(label + "不能为 null 或负数");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于零");
        }
        return value;
    }

    private <T> void runTask(Handle<T> handle, ManagedTask<T> task, Map<String, String> submittedMdc) {
        Semaphore globalGate = gateFor(handle.spec().workload());
        Semaphore serialGate = serialGateFor(handle.spec());
        boolean serialAcquired = false;
        boolean globalAcquired = false;
        try {
            if (serialGate != null) {
                serialGate.acquire();
                serialAcquired = true;
            }
            if (globalGate != null) {
                globalGate.acquire();
                globalAcquired = true;
            }
            if (!handle.start()) {
                return;
            }

            withMdc(submittedMdc, () -> {
                TaskContext context = new TaskContext(handle.id(), handle::isCancellationRequested);
                T result = TaskContext.callWith(context, () -> task.run(context));
                handle.succeed(result);
                return null;
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!handle.isCancellationRequested()) {
                handle.fail(interrupted);
            }
        } catch (CancellationException cancelled) {
            handle.cancelWith(cancelled);
        } catch (Throwable failure) {
            handle.fail(failure);
        } finally {
            if (globalAcquired) {
                globalGate.release();
            }
            if (serialAcquired) {
                serialGate.release();
            }
            handle.markTerminated();
        }
    }

    void awaitTasks(Collection<? extends TaskHandle<?>> taskHandles, Duration timeout) {
        CompletableFuture<?>[] terminations = taskHandles.stream()
                .filter(Handle.class::isInstance)
                .map(Handle.class::cast)
                .map(Handle::termination)
                .toArray(CompletableFuture[]::new);
        if (terminations.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(terminations)
                    .get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // 关闭协议有明确时间上限；任务已收到取消和中断，不无限阻塞调用线程。
        }
    }

    private ExecutorService executorFor(Workload workload) {
        return switch (workload) {
            case IO -> ioExecutor;
            case CPU -> cpuExecutor;
            case BROWSER -> browserExecutor;
            case PROCESS -> processExecutor;
        };
    }

    private Semaphore gateFor(Workload workload) {
        return switch (workload) {
            case IO -> ioGate;
            case BROWSER -> browserGate;
            case PROCESS -> processGate;
            case CPU -> null;
        };
    }

    private Semaphore serialGateFor(TaskSpec spec) {
        if (spec.workload() != Workload.BROWSER || spec.serializationKey() == null) {
            return null;
        }
        return browserSerialGates.computeIfAbsent(spec.serializationKey(), ignored -> new Semaphore(1, true));
    }

    private static <T> T withMdc(Map<String, String> submitted, ThrowingSupplier<T> action)
            throws Exception {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (submitted == null || submitted.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(submitted);
            }
            return action.get();
        } finally {
            if (previous == null || previous.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        for (Handle<?> handle : handles.values()) {
            handle.cancel();
        }
        for (ScheduledTrigger trigger : triggers) {
            trigger.cancel();
        }
        scheduler.shutdownNow();
        ioExecutor.shutdownNow();
        cpuExecutor.shutdownNow();
        browserExecutor.shutdownNow();
        processExecutor.shutdownNow();

        long deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
        await(ioExecutor, deadline);
        await(cpuExecutor, deadline);
        await(browserExecutor, deadline);
        await(processExecutor, deadline);
        await(scheduler, deadline);
        handles.clear();
        triggers.clear();
        browserSerialGates.clear();
    }

    private void await(ExecutorService executor, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService virtualExecutor(String prefix) {
        ThreadFactory factory = Thread.ofVirtual()
                .name(prefix, 0)
                .inheritInheritableThreadLocals(false)
                .factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    private static ThreadFactory platformFactory(String prefix) {
        return Thread.ofPlatform().daemon(true).name(prefix, 0).factory();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class Handle<T> implements TaskHandle<T> {
        private final String id;
        private final TaskSpec spec;
        private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.QUEUED);
        private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> termination = new CompletableFuture<>();
        private volatile Future<?> execution;
        private volatile ScheduledFuture<?> timeout;

        private Handle(String id, TaskSpec spec) {
            this.id = id;
            this.spec = spec;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public TaskSpec spec() {
            return spec;
        }

        @Override
        public TaskState state() {
            return state.get();
        }

        @Override
        public CompletableFuture<T> completion() {
            return completion;
        }

        private boolean start() {
            return state.compareAndSet(TaskState.QUEUED, TaskState.RUNNING);
        }

        private void succeed(T result) {
            if (state.compareAndSet(TaskState.RUNNING, TaskState.SUCCEEDED)) {
                completion.complete(result);
            }
        }

        private void fail(Throwable failure) {
            while (true) {
                TaskState current = state.get();
                if (current.isTerminal()) {
                    return;
                }
                if (state.compareAndSet(current, TaskState.FAILED)) {
                    completion.completeExceptionally(failure);
                    cancelExecution(false);
                    if (current == TaskState.QUEUED) {
                        markTerminated();
                    }
                    return;
                }
            }
        }

        @Override
        public boolean cancel() {
            return cancelWith(new CancellationException("任务已取消: " + spec.name()));
        }

        private boolean cancelWith(Throwable reason) {
            cancellationRequested.set(true);
            while (true) {
                TaskState current = state.get();
                if (current.isTerminal()) {
                    return false;
                }
                if (state.compareAndSet(current, TaskState.CANCELLED)) {
                    cancelExecution(true);
                    completion.completeExceptionally(reason);
                    if (current == TaskState.QUEUED) {
                        markTerminated();
                    }
                    return true;
                }
            }
        }

        private void timeout() {
            cancelWith(new TimeoutException("任务超时: " + spec.name() + " (" + spec.timeout() + ")"));
        }

        private boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        private void bindExecution(Future<?> value) {
            execution = value;
            if (state.get().isTerminal()) {
                value.cancel(true);
            }
        }

        private void bindTimeout(ScheduledFuture<?> value) {
            timeout = value;
            if (state.get().isTerminal()) {
                value.cancel(false);
            }
        }

        private void cancelTimeout() {
            ScheduledFuture<?> value = timeout;
            if (value != null) {
                value.cancel(false);
            }
        }

        private void cancelExecution(boolean interrupt) {
            Future<?> value = execution;
            if (value != null) {
                value.cancel(interrupt);
            }
        }

        private CompletableFuture<Void> termination() {
            return termination;
        }

        private void markTerminated() {
            termination.complete(null);
        }
    }

    private final class ScheduledTrigger implements TriggerHandle {
        private final boolean oneShot;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> future;

        private ScheduledTrigger(boolean oneShot) {
            this.oneShot = oneShot;
        }

        private void bind(ScheduledFuture<?> value) {
            future = value;
            if (cancelled.get()) {
                value.cancel(false);
            }
        }

        private void finish() {
            if (cancelled.compareAndSet(false, true)) {
                triggers.remove(this);
            }
        }

        @Override
        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            ScheduledFuture<?> value = future;
            if (value != null) {
                value.cancel(false);
            }
            triggers.remove(this);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
