package com.javaclaw.workflow.runtime;

import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.execution.TaskSubmitter;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.store.GraphCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 工作区级图执行生命周期管理器。 */
public final class GraphExecutionManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GraphExecutionManager.class);
    public static final String RESUME_NODE_STATE_KEY = "_workflow.resumeNode";
    private final NodeExecutorRegistry registry;
    private final GraphCheckpointStore store;
    private final TaskSubmitter tasks;
    private final WorkflowExtensionPlanProvider extensionPlans;
    private final ConcurrentHashMap<String, CancellationToken> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TaskHandle<Void>> handles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkflowExtensionPlan> retainedPlans =
            new ConcurrentHashMap<>();
    /** 同一 thread 同时只允许一个运行，避免共享 thread state 被并发覆盖。 */
    private final ConcurrentHashMap<String, String> activeThreads = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public GraphExecutionManager(NodeExecutorRegistry registry, GraphCheckpointStore store,
                                 TaskSubmitter tasks) {
        this(registry, store, tasks, WorkflowExtensionPlanProvider.NONE);
    }

    public GraphExecutionManager(NodeExecutorRegistry registry, GraphCheckpointStore store,
                                 TaskSubmitter tasks,
                                 WorkflowExtensionPlanProvider extensionPlans) {
        this.registry = Objects.requireNonNull(registry);
        this.store = Objects.requireNonNull(store);
        this.tasks = Objects.requireNonNull(tasks);
        this.extensionPlans = Objects.requireNonNull(extensionPlans);
        store.markRunningAsRecoveryRequired();
        restoreNonTerminalPlanLeases();
    }

    public GraphRun start(GraphDefinition definition, String threadId, GraphState initialState,
                          GraphListener listener, WorkflowExecutionServices services) {
        WorkflowExtensionPlan plan = extensionPlans.compile(definition);
        NodeExecutorRegistry exactRegistry = registry.fixedOverlay(plan::find);
        GraphRun run = null;
        CancellationToken token = null;
        boolean persisted = false;
        try {
            GraphValidator.requireValid(definition, exactRegistry);
            run = new GraphRun(definition, threadId, initialState, plan.locks());
            retainPlan(run.id(), plan);
            run.status(RunStatus.RUNNING);
            token = reserve(run);
            store.createRunningRun(run);
            persisted = true;
            schedule(run, token, listener, services, exactRegistry);
            return run;
        } catch (RuntimeException | Error failure) {
            if (run != null && token != null) release(run, token);
            if (run != null && persisted) markSchedulingFailure(run, failure);
            if (run != null) releasePlan(run.id());
            else plan.close();
            throw failure;
        }
    }

    public GraphRun resume(String runId, String humanResponse, boolean unsafeRetryConfirmed,
                           GraphListener listener, WorkflowExecutionServices services) {
        GraphRun run = store.loadRun(runId);
        if (run == null) throw new IllegalArgumentException("运行记录不存在: " + runId);
        if (run.status().terminal()) throw new IllegalStateException("终态运行不可恢复: " + run.status());
        WorkflowExtensionPlan plan = retainedPlans.get(runId);
        if (plan == null) {
            plan = extensionPlans.restore(run.extensionLocks()).orElse(null);
            if (plan == null) {
                run.status(RunStatus.RECOVERY_BLOCKED_MISSING_EXTENSION);
                run.error("恢复被阻塞：缺少运行锁定的扩展版本");
                store.updateRun(run);
                throw new IllegalStateException(run.error());
            }
            retainPlan(runId, plan);
        }
        NodeExecutorRegistry exactRegistry = registry.fixedOverlay(plan::find);
        GraphValidator.requireValid(run.definition(), exactRegistry);
        RunStatus expectedStatus = run.status();
        if (expectedStatus == RunStatus.WAITING_INPUT) {
            if (humanResponse == null) throw new IllegalArgumentException("恢复待输入工作流必须提供响应");
        } else if (expectedStatus == RunStatus.RECOVERY_REQUIRED
                || expectedStatus == RunStatus.RECOVERY_BLOCKED_MISSING_EXTENSION) {
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
            } else if ((expectedStatus == RunStatus.RECOVERY_REQUIRED
                    || expectedStatus == RunStatus.RECOVERY_BLOCKED_MISSING_EXTENSION)
                    && run.nextNodeId() == null) {
                run.nextNodeId(run.currentNodeId());
            }
            run.status(RunStatus.RUNNING);
            store.activateExistingRun(run, expectedStatus);
            activated = true;
            schedule(run, token, listener, services, exactRegistry);
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
        releasePlan(runId);
        return true;
    }

    public boolean pause(String runId) {
        CancellationToken token = active.get(runId);
        return token != null && token.requestPause();
    }

    public GraphRun load(String runId) { return store.loadRun(runId); }
    public boolean isActive(String runId) { return active.containsKey(runId); }

    private CancellationToken reserve(GraphRun run) {
        if (!accepting.get()) {
            throw new RejectedExecutionException("工作流执行器已关闭");
        }
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
                          GraphListener listener, WorkflowExecutionServices services,
                          NodeExecutorRegistry exactRegistry) {
        TaskHandle<Void> handle = tasks.submit(TaskSpec.io("workflow-run-" + run.id()), context -> {
            context.cancellation().throwIfCancellationRequested();
            AtomicReference<GraphEvent.RunFinished> terminal = new AtomicReference<>();
            GraphListener lifecycleListener = event -> {
                if (event instanceof GraphEvent.RunFinished finished) terminal.set(finished);
                else if (listener != null) listener.onEvent(event);
            };
            try {
                new GraphEngine(exactRegistry, store)
                        .execute(run, token, lifecycleListener, services);
            } finally {
                // 终态回调可能立刻为同一 thread 启动下一条排队运行；必须先释放两级占用，
                // 否则旧 run 会覆盖新 run 的映射，或让回调误判 thread 仍忙。
                release(run, token);
                if (run.status().terminal()) releasePlan(run.id());
                GraphEvent.RunFinished finished = terminal.get();
                if (finished != null && listener != null) {
                    try {
                        listener.onEvent(finished);
                    } catch (Throwable listenerFailure) {
                        log.debug("工作流终态监听器执行失败: run={}", run.id(), listenerFailure);
                    }
                }
            }
            return null;
        });
        handles.put(run.id(), handle);
        handle.completion().whenComplete((ignored, failure) -> handles.remove(run.id(), handle));
        if (!accepting.get()) {
            token.cancel();
            handle.cancel();
        }
    }

    private void release(GraphRun run, CancellationToken token) {
        active.remove(run.id(), token);
        activeThreads.remove(run.threadId(), run.id());
    }

    private void markSchedulingFailure(GraphRun run, Throwable failure) {
        run.status(RunStatus.FAILED);
        run.error("工作流任务提交失败: " + failure.getMessage());
        try {
            store.updateRun(run);
        } catch (Throwable persistFailure) {
            failure.addSuppressed(persistFailure);
        }
        releasePlan(run.id());
    }

    private void retainPlan(String runId, WorkflowExtensionPlan plan) {
        if (plan.locks().isEmpty()) return;
        WorkflowExtensionPlan previous = retainedPlans.putIfAbsent(runId, plan);
        if (previous != null && previous != plan) plan.close();
    }

    private void restoreNonTerminalPlanLeases() {
        for (GraphRun run : store.listNonTerminalRuns()) {
            if (run.extensionLocks().isEmpty()) continue;
            WorkflowExtensionPlan plan = extensionPlans.restore(run.extensionLocks()).orElse(null);
            if (plan != null) {
                retainPlan(run.id(), plan);
                continue;
            }
            run.status(RunStatus.RECOVERY_BLOCKED_MISSING_EXTENSION);
            run.error("恢复被阻塞：缺少运行锁定的扩展版本");
            store.updateRun(run);
        }
    }

    private void releasePlan(String runId) {
        WorkflowExtensionPlan plan = retainedPlans.remove(runId);
        if (plan != null) plan.close();
    }

    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) return;
        for (CancellationToken token : active.values()) token.cancel();
        List<TaskHandle<Void>> snapshot = List.copyOf(handles.values());
        awaitGracefulCompletion(snapshot, Duration.ofSeconds(5));
        for (TaskHandle<Void> handle : snapshot) {
            if (!handle.state().isTerminal()) handle.cancel();
        }
        active.clear();
        activeThreads.clear();
        handles.clear();
        retainedPlans.values().forEach(plan -> {
            try { plan.close(); }
            catch (RuntimeException failure) {
                log.debug("工作流扩展计划释放失败", failure);
            }
        });
        retainedPlans.clear();
    }

    private static void awaitGracefulCompletion(
            List<? extends TaskHandle<?>> taskHandles, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (TaskHandle<?> handle : taskHandles) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            try {
                handle.completion().get(remaining, TimeUnit.NANOSECONDS);
            } catch (ExecutionException ignored) {
                // 失败也是终态；继续等待其他运行完成自身清理。
            } catch (TimeoutException ignored) {
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
