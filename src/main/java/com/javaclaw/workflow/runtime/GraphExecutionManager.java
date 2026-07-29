package com.javaclaw.workflow.runtime;

import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.store.GraphCheckpointStore;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 工作区级图执行生命周期管理器。 */
public final class GraphExecutionManager implements AutoCloseable {
    public static final String RESUME_NODE_STATE_KEY = "_workflow.resumeNode";
    private final NodeExecutorRegistry registry;
    private final GraphCheckpointStore store;
    private final GraphEngine engine;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, CancellationToken> active = new ConcurrentHashMap<>();
    /** 同一 thread 同时只允许一个运行，避免共享 thread state 被并发覆盖。 */
    private final ConcurrentHashMap<String, String> activeThreads = new ConcurrentHashMap<>();

    public GraphExecutionManager(NodeExecutorRegistry registry, GraphCheckpointStore store) {
        this(registry, store, Executors.newVirtualThreadPerTaskExecutor());
    }

    public GraphExecutionManager(NodeExecutorRegistry registry, GraphCheckpointStore store,
                                 ExecutorService executor) {
        this.registry = Objects.requireNonNull(registry);
        this.store = Objects.requireNonNull(store);
        this.executor = Objects.requireNonNull(executor);
        this.engine = new GraphEngine(this.registry, store);
        store.markRunningAsRecoveryRequired();
    }

    public GraphRun start(GraphDefinition definition, String threadId, GraphState initialState,
                          GraphListener listener, Map<Class<?>, Object> services) {
        GraphValidator.requireValid(definition, registry);
        GraphRun run = new GraphRun(definition, threadId, initialState);
        run.status(RunStatus.RUNNING);
        CancellationToken token = reserve(run);
        boolean persisted = false;
        try {
            store.createRunningRun(run);
            persisted = true;
            schedule(run, token, listener, services);
            return run;
        } catch (RuntimeException | Error failure) {
            release(run, token);
            if (persisted) markSchedulingFailure(run, failure);
            throw failure;
        }
    }

    public GraphRun resume(String runId, String humanResponse, boolean unsafeRetryConfirmed,
                           GraphListener listener, Map<Class<?>, Object> services) {
        GraphRun run = store.loadRun(runId);
        if (run == null) throw new IllegalArgumentException("运行记录不存在: " + runId);
        if (run.status().terminal()) throw new IllegalStateException("终态运行不可恢复: " + run.status());
        RunStatus expectedStatus = run.status();
        if (expectedStatus == RunStatus.WAITING_INPUT) {
            if (humanResponse == null) throw new IllegalArgumentException("恢复待输入工作流必须提供响应");
        } else if (expectedStatus == RunStatus.RECOVERY_REQUIRED) {
            var node = run.definition().nodes().stream()
                    .filter(n -> n.id().equals(run.currentNodeId())).findFirst().orElse(null);
            if (node != null && node.resumeSafety() == ResumeSafety.CONFIRM_RETRY && !unsafeRetryConfirmed) {
                throw new SecurityException("副作用节点恢复前必须重新确认");
            }
        }
        CancellationToken token = reserve(run);
        boolean activated = false;
        try {
            if (expectedStatus == RunStatus.WAITING_INPUT) {
                String key = run.interrupt() == null ? "human.response" : run.interrupt().responseKey();
                run.state(run.state().apply(StatePatch.builder()
                        .set(key, humanResponse)
                        .set(RESUME_NODE_STATE_KEY, run.currentNodeId())
                        .build()));
                run.nextNodeId(run.currentNodeId());
                run.interrupt(null);
            } else if (expectedStatus == RunStatus.RECOVERY_REQUIRED
                    && run.nextNodeId() == null) {
                run.nextNodeId(run.currentNodeId());
            }
            run.status(RunStatus.RUNNING);
            store.activateExistingRun(run, expectedStatus);
            activated = true;
            schedule(run, token, listener, services);
            return run;
        } catch (RuntimeException | Error failure) {
            release(run, token);
            if (activated) markSchedulingFailure(run, failure);
            throw failure;
        }
    }

    public boolean cancel(String runId) {
        CancellationToken token = active.get(runId);
        if (token != null) return token.cancel();
        GraphRun run = store.loadRun(runId);
        if (run == null || run.status().terminal()) return false;
        run.status(RunStatus.CANCELLED);
        store.updateRun(run);
        return true;
    }

    public boolean pause(String runId) {
        CancellationToken token = active.get(runId);
        return token != null && token.requestPause();
    }

    public GraphRun load(String runId) { return store.loadRun(runId); }
    public boolean isActive(String runId) { return active.containsKey(runId); }

    private CancellationToken reserve(GraphRun run) {
        CancellationToken token = new CancellationToken();
        if (active.putIfAbsent(run.id(), token) != null) {
            throw new IllegalStateException("运行已在执行: " + run.id());
        }
        String occupyingRun = activeThreads.putIfAbsent(run.threadId(), run.id());
        if (occupyingRun != null) {
            active.remove(run.id(), token);
            throw new IllegalStateException(
                    "同一工作流 thread 已有运行在执行: thread=" + run.threadId()
                            + ", run=" + occupyingRun);
        }
        return token;
    }

    private void schedule(GraphRun run, CancellationToken token,
                          GraphListener listener, Map<Class<?>, Object> services) {
        executor.submit(() -> {
            AtomicReference<GraphEvent.RunFinished> terminal = new AtomicReference<>();
            GraphListener lifecycleListener = event -> {
                if (event instanceof GraphEvent.RunFinished finished) terminal.set(finished);
                else if (listener != null) listener.onEvent(event);
            };
            try {
                engine.execute(run, token, lifecycleListener, services);
            } finally {
                // 终态回调可能立刻为同一 thread 启动下一条排队运行；必须先释放两级占用，
                // 否则旧 run 会覆盖新 run 的映射，或让回调误判 thread 仍忙。
                release(run, token);
                GraphEvent.RunFinished finished = terminal.get();
                if (finished != null && listener != null) {
                    try { listener.onEvent(finished); }
                    catch (Throwable ignored) { }
                }
            }
        });
    }

    private void release(GraphRun run, CancellationToken token) {
        active.remove(run.id(), token);
        activeThreads.remove(run.threadId(), run.id());
    }

    private void markSchedulingFailure(GraphRun run, Throwable failure) {
        run.status(RunStatus.FAILED);
        run.error("工作流执行线程提交失败: " + failure.getMessage());
        try {
            store.updateRun(run);
        } catch (Throwable persistFailure) {
            failure.addSuppressed(persistFailure);
        }
    }

    @Override
    public void close() {
        for (CancellationToken token : active.values()) token.cancel();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        } finally {
            active.clear();
            activeThreads.clear();
        }
    }
}
