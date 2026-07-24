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
    private final GraphCheckpointStore store;
    private final GraphEngine engine;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, CancellationToken> active = new ConcurrentHashMap<>();

    public GraphExecutionManager(NodeExecutorRegistry registry, GraphCheckpointStore store) {
        this.store = Objects.requireNonNull(store);
        this.engine = new GraphEngine(Objects.requireNonNull(registry), store);
        store.markRunningAsRecoveryRequired();
    }

    public GraphRun start(GraphDefinition definition, String threadId, GraphState initialState,
                          GraphListener listener, Map<Class<?>, Object> services) {
        GraphRun run = new GraphRun(definition, threadId, initialState);
        store.createRun(run);
        schedule(run, listener, services);
        return run;
    }

    public GraphRun resume(String runId, String humanResponse, boolean unsafeRetryConfirmed,
                           GraphListener listener, Map<Class<?>, Object> services) {
        GraphRun run = store.loadRun(runId);
        if (run == null) throw new IllegalArgumentException("运行记录不存在: " + runId);
        if (run.status().terminal()) throw new IllegalStateException("终态运行不可恢复: " + run.status());
        if (active.containsKey(runId)) throw new IllegalStateException("运行仍在执行: " + runId);

        if (run.status() == RunStatus.WAITING_INPUT) {
            if (humanResponse == null) throw new IllegalArgumentException("恢复待输入工作流必须提供响应");
            String key = run.interrupt() == null ? "human.response" : run.interrupt().responseKey();
            run.state(run.state().apply(StatePatch.builder()
                    .set(key, humanResponse)
                    .set(RESUME_NODE_STATE_KEY, run.currentNodeId())
                    .build()));
            run.nextNodeId(run.currentNodeId());
            run.interrupt(null);
        } else if (run.status() == RunStatus.RECOVERY_REQUIRED) {
            var node = run.definition().nodes().stream()
                    .filter(n -> n.id().equals(run.currentNodeId())).findFirst().orElse(null);
            if (node != null && node.resumeSafety() == ResumeSafety.CONFIRM_RETRY && !unsafeRetryConfirmed) {
                throw new SecurityException("副作用节点恢复前必须重新确认");
            }
            if (run.nextNodeId() == null) run.nextNodeId(run.currentNodeId());
        }
        run.status(RunStatus.CREATED);
        store.updateRun(run);
        schedule(run, listener, services);
        return run;
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

    private void schedule(GraphRun run, GraphListener listener, Map<Class<?>, Object> services) {
        CancellationToken token = new CancellationToken();
        if (active.putIfAbsent(run.id(), token) != null) {
            throw new IllegalStateException("运行已在执行: " + run.id());
        }
        executor.submit(() -> {
            AtomicReference<GraphEvent.RunFinished> terminal = new AtomicReference<>();
            GraphListener lifecycleListener = event -> {
                if (event instanceof GraphEvent.RunFinished finished) terminal.set(finished);
                else if (listener != null) listener.onEvent(event);
            };
            try {
                engine.execute(run, token, lifecycleListener, services);
            } finally {
                // 终态回调可能立刻为同一 thread 启动下一条排队运行；必须先释放 active，
                // 否则旧 run 会覆盖新 run 的映射，或让回调误判 thread 仍忙。
                active.remove(run.id(), token);
                GraphEvent.RunFinished finished = terminal.get();
                if (finished != null && listener != null) {
                    try { listener.onEvent(finished); }
                    catch (Throwable ignored) { }
                }
            }
        });
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
        }
    }
}
