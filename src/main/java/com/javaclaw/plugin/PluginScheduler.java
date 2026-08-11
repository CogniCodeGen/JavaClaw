package com.javaclaw.plugin;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.execution.TriggerHandle;
import com.javaclaw.plugin.api.PluginException;
import com.javaclaw.plugin.api.exec.PluginCallable;
import com.javaclaw.plugin.api.exec.PluginExecutor;
import com.javaclaw.plugin.api.exec.PluginTask;
import com.javaclaw.plugin.api.exec.TaskContext;
import com.javaclaw.plugin.api.exec.TaskHandle;
import com.javaclaw.plugin.api.exec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plugin API 3.0 执行适配器。
 *
 * <p>所有任务正文复用进程级 {@link ManagedTaskExecutor} 的 I/O 虚拟线程池；本类只增加插件身份、
 * 独立并发配额、统一 API 句柄和卸载边界。延时及固定频率任务复用根级单线程触发器，触发线程
 * 从不运行插件代码。</p>
 *
 * <p>实例线程安全。{@link #shutdown()} 后拒绝新任务，取消该插件的全部任务并等待作用域回收，
 * 不影响宿主、工作区或其他插件。</p>
 */
public final class PluginScheduler implements PluginExecutor {

    private static final Logger log = LoggerFactory.getLogger(PluginScheduler.class);

    private final String pluginId;
    private final PluginScope.PluginIdentity identity;
    private final ManagedTaskExecutor executor;
    private final TaskScope scope;
    private final Set<ApiTaskHandle<?>> liveHandles = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    PluginScheduler(String pluginId, PluginScope.PluginIdentity identity,
                    int maxConcurrentTasks, ManagedTaskExecutor executor) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("插件 id 不能为空");
        }
        this.pluginId = pluginId;
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.scope = executor.openScope("plugin-" + pluginId, maxConcurrentTasks);
        log.info("插件[{}]任务作用域已创建（并发上限 {}）", pluginId, maxConcurrentTasks);
    }

    @Override
    public TaskHandle<Void> submit(String name, PluginTask task) {
        java.util.Objects.requireNonNull(task, "task");
        return submitCallable(name, context -> {
            task.run(context);
            return null;
        });
    }

    @Override
    public <T> T call(String name, PluginCallable<T> task) throws Exception {
        java.util.Objects.requireNonNull(task, "task");
        ApiTaskHandle<T> handle = submitCallable(name, task);
        try {
            return handle.completion().toCompletableFuture().get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            handle.cancel();
            throw interrupted;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (ExecutionException failed) {
            Throwable cause = unwrap(failed);
            log.warn("插件[{}]同步调用[{}]失败：{}", pluginId, name, cause.toString());
            throw new PluginException.PluginExecException(
                    "插件[" + pluginId + "]同步调用失败: " + name, cause);
        }
    }

    @Override
    public TaskHandle<Void> background(String name, PluginTask task) {
        return submit(name, task);
    }

    @Override
    public TaskHandle<Void> schedule(String name, Duration delay, PluginTask task) {
        ensureAccepting();
        java.util.Objects.requireNonNull(task, "task");
        ApiTaskHandle<Void> handle = register(name);
        try {
            TriggerHandle trigger = executor.scheduleTrigger(delay,
                    () -> dispatchOneShot(handle, task));
            handle.bindTrigger(trigger);
            return handle;
        } catch (RuntimeException failure) {
            handle.fail(failure);
            throw failure;
        }
    }

    @Override
    public TaskHandle<Void> scheduleAtFixedRate(
            String name, Duration initialDelay, Duration period, PluginTask task) {
        ensureAccepting();
        java.util.Objects.requireNonNull(task, "task");
        ApiTaskHandle<Void> handle = register(name);
        AtomicBoolean iterationRunning = new AtomicBoolean(false);
        try {
            TriggerHandle trigger = executor.scheduleTriggerAtFixedRate(initialDelay, period, () -> {
                if (!handle.canRun() || !iterationRunning.compareAndSet(false, true)) {
                    return;
                }
                handle.markRunning();
                com.javaclaw.platform.execution.TaskHandle<Void> delegate;
                try {
                    delegate = submitPlatform(handle, task);
                } catch (RuntimeException failure) {
                    iterationRunning.set(false);
                    handle.fail(failure);
                    return;
                }
                delegate.completion().whenComplete((ignored, failure) -> {
                    handle.unbind(delegate);
                    iterationRunning.set(false);
                    if (failure != null && handle.canRun()) {
                        if (delegate.state()
                                == com.javaclaw.platform.execution.TaskState.CANCELLED) {
                            handle.cancelFromHost();
                        } else {
                            handle.fail(unwrap(failure));
                        }
                    }
                });
            });
            handle.bindTrigger(trigger);
            return handle;
        } catch (RuntimeException failure) {
            handle.fail(failure);
            throw failure;
        }
    }

    private <T> ApiTaskHandle<T> submitCallable(String name, PluginCallable<T> task) {
        ensureAccepting();
        ApiTaskHandle<T> handle = register(name);
        try {
            com.javaclaw.platform.execution.TaskHandle<T> delegate = scope.submit(
                    TaskSpec.io(platformName(handle.name)), platformContext -> {
                        handle.markRunning();
                        TaskContext context = pluginContext(handle, platformContext);
                        return ScopedValue.where(PluginScope.CURRENT, identity)
                                .call(() -> task.call(context));
                    });
            handle.bind(delegate);
            delegate.completion().whenComplete((result, failure) -> {
                handle.unbind(delegate);
                if (failure == null) {
                    handle.succeed(result);
                } else if (delegate.state()
                        == com.javaclaw.platform.execution.TaskState.CANCELLED) {
                    handle.cancelFromHost();
                } else {
                    handle.fail(unwrap(failure));
                }
            });
            return handle;
        } catch (RuntimeException failure) {
            handle.fail(failure);
            throw failure;
        }
    }

    private void dispatchOneShot(ApiTaskHandle<Void> handle, PluginTask task) {
        if (!handle.canRun()) {
            return;
        }
        handle.markRunning();
        com.javaclaw.platform.execution.TaskHandle<Void> delegate;
        try {
            delegate = submitPlatform(handle, task);
        } catch (RuntimeException failure) {
            handle.fail(failure);
            return;
        }
        delegate.completion().whenComplete((ignored, failure) -> {
            handle.unbind(delegate);
            if (failure == null) {
                handle.succeed(null);
            } else if (delegate.state()
                    == com.javaclaw.platform.execution.TaskState.CANCELLED) {
                handle.cancelFromHost();
            } else {
                handle.fail(unwrap(failure));
            }
        });
    }

    private com.javaclaw.platform.execution.TaskHandle<Void> submitPlatform(
            ApiTaskHandle<?> handle, PluginTask task) {
        com.javaclaw.platform.execution.TaskHandle<Void> delegate = scope.submit(
                TaskSpec.io(platformName(handle.name)), platformContext -> {
                    TaskContext context = pluginContext(handle, platformContext);
                    ScopedValue.where(PluginScope.CURRENT, identity).call(() -> {
                        task.run(context);
                        return null;
                    });
                    return null;
                });
        handle.bind(delegate);
        return delegate;
    }

    private TaskContext pluginContext(
            ApiTaskHandle<?> handle,
            com.javaclaw.platform.execution.TaskContext platformContext) {
        return new TaskContext(handle.id(), () -> handle.isCancellationRequested()
                || platformContext.cancellation().isCancellationRequested());
    }

    private <T> ApiTaskHandle<T> register(String name) {
        String checkedName = requireName(name);
        ApiTaskHandle<T> handle = new ApiTaskHandle<>(checkedName);
        liveHandles.add(handle);
        handle.completion().whenComplete((ignored, failure) -> liveHandles.remove(handle));
        if (!accepting.get()) {
            handle.cancelFromHost();
            throw new RejectedExecutionException("插件[" + pluginId + "]执行器已关闭");
        }
        return handle;
    }

    private String platformName(String taskName) {
        return "plugin-" + pluginId + ":" + taskName;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("插件任务名称不能为空");
        }
        return name.strip();
    }

    private void ensureAccepting() {
        if (!accepting.get()) {
            throw new RejectedExecutionException("插件[" + pluginId + "]执行器已关闭");
        }
    }

    void cancelAllHandles() {
        for (ApiTaskHandle<?> handle : Set.copyOf(liveHandles)) {
            handle.cancel();
        }
    }

    int activeHandleCount() {
        return liveHandles.size();
    }

    void shutdown() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        cancelAllHandles();
        scope.close();
        liveHandles.clear();
        log.info("插件[{}]任务作用域已关闭", pluginId);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class ApiTaskHandle<T> implements TaskHandle<T> {
        private final String id = UUID.randomUUID().toString();
        private final String name;
        private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.QUEUED);
        private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private final Set<com.javaclaw.platform.execution.TaskHandle<?>> delegates =
                ConcurrentHashMap.newKeySet();
        private volatile TriggerHandle trigger;

        private ApiTaskHandle(String name) {
            this.name = name;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public TaskState state() {
            return state.get();
        }

        @Override
        public CompletionStage<T> completion() {
            return completion;
        }

        private boolean canRun() {
            return !state.get().isTerminal() && accepting.get();
        }

        private boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        private void markRunning() {
            state.compareAndSet(TaskState.QUEUED, TaskState.RUNNING);
        }

        private void bind(com.javaclaw.platform.execution.TaskHandle<?> delegate) {
            if (state.get().isTerminal()) {
                delegate.cancel();
                return;
            }
            delegates.add(delegate);
            if (state.get().isTerminal() && delegates.remove(delegate)) {
                delegate.cancel();
            }
        }

        private void unbind(com.javaclaw.platform.execution.TaskHandle<?> delegate) {
            delegates.remove(delegate);
        }

        private void bindTrigger(TriggerHandle value) {
            trigger = value;
            if (state.get().isTerminal()) {
                value.cancel();
            }
        }

        private void succeed(T value) {
            if (state.compareAndSet(TaskState.RUNNING, TaskState.SUCCEEDED)) {
                cancelResources(false);
                completion.complete(value);
            }
        }

        private void fail(Throwable failure) {
            completeExceptionally(TaskState.FAILED, failure, true);
        }

        private void cancelFromHost() {
            cancellationRequested.set(true);
            completeExceptionally(TaskState.CANCELLED,
                    new CancellationException("插件任务已由宿主取消: " + name), true);
        }

        @Override
        public boolean cancel() {
            cancellationRequested.set(true);
            return completeExceptionally(TaskState.CANCELLED,
                    new CancellationException("插件任务已取消: " + name), true);
        }

        private boolean completeExceptionally(
                TaskState terminalState, Throwable failure, boolean interrupt) {
            while (true) {
                TaskState current = state.get();
                if (current.isTerminal()) {
                    return false;
                }
                if (state.compareAndSet(current, terminalState)) {
                    cancelResources(interrupt);
                    completion.completeExceptionally(failure);
                    return true;
                }
            }
        }

        private void cancelResources(boolean interrupt) {
            TriggerHandle scheduled = trigger;
            if (scheduled != null) {
                scheduled.cancel();
            }
            for (com.javaclaw.platform.execution.TaskHandle<?> delegate : delegates) {
                if (interrupt) {
                    delegate.cancel();
                }
            }
            delegates.clear();
        }
    }
}
