package com.javaclaw.workflow.service;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphKind;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.runtime.GraphEvent;
import com.javaclaw.workflow.runtime.GraphExecutionManager;
import com.javaclaw.workflow.runtime.GraphListener;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.runtime.GraphValidator;
import com.javaclaw.workflow.runtime.NodeExecutorRegistry;
import com.javaclaw.workflow.store.GraphCheckpointStore;
import com.javaclaw.workflow.store.WorkflowDefinitionRecord;
import com.javaclaw.workflow.store.WorkflowDefinitionStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 自定义工作流门面：定义、发布、会话 thread、运行及聊天事件桥。 */
public final class WorkflowService implements AutoCloseable {
    private final String workspaceId;
    private final AgentRuntime agentRuntime;
    private final NodeExecutorRegistry nodeRegistry;
    private final WorkflowDefinitionStore definitions;
    private final GraphCheckpointStore checkpoints;
    private final GraphExecutionManager executions;
    private final SystemGraphRegistry systemGraphs;
    private final ConcurrentHashMap<String, String> activeByThread = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingRecovery> pendingRecoveryByThread = new ConcurrentHashMap<>();

    public WorkflowService(String workspaceId, AgentRuntime agentRuntime,
                           NodeExecutorRegistry nodeRegistry, WorkflowDefinitionStore definitions,
                           GraphCheckpointStore checkpoints, SystemGraphRegistry systemGraphs) {
        this.workspaceId = Objects.requireNonNull(workspaceId);
        this.agentRuntime = Objects.requireNonNull(agentRuntime);
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry);
        this.definitions = Objects.requireNonNull(definitions);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.executions = new GraphExecutionManager(nodeRegistry, checkpoints);
        this.systemGraphs = Objects.requireNonNull(systemGraphs);
    }

    public synchronized GraphRun startOrResume(String workflowId, String sessionId, String input,
                                               ConversationCallbacks callbacks) {
        WorkflowDefinitionRecord record = definitions.get(workflowId);
        if (record == null || !record.isPublished() || record.archived()) {
            throw new IllegalStateException("工作流未发布或已归档: " + workflowId);
        }
        String thread = threadId(sessionId, workflowId);
        requireIdleThread(thread);
        GraphRun waiting = checkpoints.findWaitingRun(workflowId, thread);
        GraphListener listener = bridge(callbacks, thread);
        Map<Class<?>, Object> services = Map.of(AgentRuntime.class, agentRuntime,
                ConversationCallbacks.class, callbacks);
        GraphRun run;
        if (waiting != null) {
            run = executions.resume(waiting.id(), input, false, listener, services);
        } else {
            GraphState state = checkpoints.loadThreadState(workflowId, thread)
                    .apply(StatePatch.builder().set("input", input == null ? "" : input).build());
            run = executions.start(record.published(), thread, state, listener, services);
        }
        trackActive(thread, run);
        return run;
    }

    public GraphRun testRun(GraphDefinition draft, String input, ConversationCallbacks callbacks) {
        GraphValidator.requireValid(draft, nodeRegistry);
        String thread = threadId("test-" + System.nanoTime(), draft.id());
        GraphState state = new GraphState().apply(StatePatch.builder().set("input", input).build());
        GraphRun run = executions.start(draft, thread, state, bridge(callbacks, thread),
                Map.of(AgentRuntime.class, agentRuntime, ConversationCallbacks.class, callbacks));
        trackActive(thread, run);
        return run;
    }

    public synchronized GraphRun runSystem(GraphDefinition graph, String sessionId, String input,
                                           ConversationCallbacks callbacks, SystemPipeline pipeline) {
        return runSystem(graph, sessionId, input, callbacks, pipeline,
                SystemRecoveryPolicy.RESUME_THEN_START);
    }

    public synchronized GraphRun runSystem(GraphDefinition graph, String sessionId, String input,
                                           ConversationCallbacks callbacks, SystemPipeline pipeline,
                                           SystemRecoveryPolicy recoveryPolicy) {
        GraphState invocation = new GraphState().apply(
                StatePatch.builder().set("input", input == null ? "" : input).build());
        return runSystem(graph, sessionId, invocation, callbacks, pipeline, recoveryPolicy);
    }

    public synchronized GraphRun runSystem(GraphDefinition graph, String sessionId, GraphState invocation,
                                           ConversationCallbacks callbacks, SystemPipeline pipeline) {
        return runSystem(graph, sessionId, invocation, callbacks, pipeline,
                SystemRecoveryPolicy.RESUME_THEN_START);
    }

    public synchronized GraphRun runSystem(GraphDefinition graph, String sessionId, GraphState invocation,
                                           ConversationCallbacks callbacks, SystemPipeline pipeline,
                                           SystemRecoveryPolicy recoveryPolicy) {
        String thread = threadId(sessionId, graph.id());
        requireIdleThread(thread);
        GraphRun recoverable = checkpoints.findRecoverableRun(graph.id(), thread);
        GraphRun run;
        if (recoverable != null) {
            Thread confirmationThread = Thread.ofVirtual()
                    .name("workflow-recovery-confirm-" + recoverable.id())
                    .unstarted(() -> confirmAndContinueSystem(recoverable, thread));
            PendingRecovery pending = new PendingRecovery(recoverable.id(), confirmationThread, graph,
                    invocation == null ? new GraphState() : invocation, callbacks, pipeline,
                    recoveryPolicy == null ? SystemRecoveryPolicy.RESUME_THEN_START : recoveryPolicy);
            pendingRecoveryByThread.put(thread, pending);
            confirmationThread.start();
            return recoverable;
        } else {
            run = startSystemInvocation(graph, thread, invocation, callbacks, pipeline);
        }
        return run;
    }

    /** 从工作流中心恢复暂停、人工中断或异常退出的运行。 */
    public GraphRun resumeRun(String runId, String input, boolean unsafeRetryConfirmed,
                              ConversationCallbacks callbacks) {
        GraphRun saved = checkpoints.loadRun(runId);
        if (saved == null) throw new IllegalArgumentException("运行记录不存在: " + runId);
        if (saved.definition().kind() == GraphKind.SYSTEM) {
            throw new IllegalStateException("系统图必须从对应的聊天、规划、循环或 SDD 模式恢复");
        }
        GraphListener listener = bridge(callbacks, saved.threadId());
        GraphRun run = executions.resume(runId, input, unsafeRetryConfirmed, listener,
                Map.of(AgentRuntime.class, agentRuntime, ConversationCallbacks.class, callbacks));
        trackActive(saved.threadId(), run);
        return run;
    }

    public boolean cancelRun(String runId) { return executions.cancel(runId); }

    public synchronized boolean cancelSystem(String workflowId, String sessionId) {
        String thread = threadId(sessionId, workflowId);
        PendingRecovery pending = pendingRecoveryByThread.remove(thread);
        if (pending != null) return cancelPendingRecovery(pending);
        String runId = activeByThread.get(thread);
        if (runId != null) return executions.cancel(runId);
        return false;
    }

    public boolean cancel(String workflowId, String sessionId) {
        String runId = activeByThread.get(threadId(sessionId, workflowId));
        return runId != null && executions.cancel(runId);
    }

    public boolean pauseRun(String runId) { return executions.pause(runId); }
    public GraphRun loadRun(String runId) { return executions.load(runId); }
    public List<GraphRun> listRuns(String workflowId, int limit) { return checkpoints.listRuns(workflowId, limit); }
    public WorkflowDefinitionStore definitions() { return definitions; }
    public NodeExecutorRegistry nodeRegistry() { return nodeRegistry; }
    public SystemGraphRegistry systemGraphs() { return systemGraphs; }

    private GraphListener bridge(ConversationCallbacks callbacks, String thread) {
        return event -> {
            sendEvent(callbacks, new ConversationEvent.Custom("graph_trace", event));
            if (event instanceof GraphEvent.NodeStarted e) {
                sendEvent(callbacks, new ConversationEvent.Progress(
                        "graph:" + e.nodeId(), e.label(),
                        ConversationEvent.Progress.Status.RUNNING, null));
            } else if (event instanceof GraphEvent.NodeCompleted e) {
                sendEvent(callbacks, new ConversationEvent.Progress(
                        "graph:" + e.nodeId(), e.label(),
                        ConversationEvent.Progress.Status.DONE, null));
            } else if (event instanceof GraphEvent.Interrupted e) {
                sendEvent(callbacks, new ConversationEvent.Custom("workflow_interrupt", e));
                // WAITING_INPUT 仍需结束当前聊天流以重新开放输入框。发送可持久化的
                // Reply 后再 onComplete，避免 UI 把空回复误写成“模型未返回有效回复”。
                sendEvent(callbacks, new ConversationEvent.Reply(e.prompt()));
            } else if (event instanceof GraphEvent.RunFinished e) {
                activeByThread.remove(thread, e.runId());
                if (e.status() == RunStatus.FAILED) sendError(callbacks, new IllegalStateException(e.error()));
                else sendComplete(callbacks);
            }
        };
    }

    private void trackActive(String thread, GraphRun run) {
        // 极短图可能在 start/resume 返回前结束，甚至已由终态监听器启动下一条排队运行。
        // 先判断再写，避免把新运行的映射覆盖为已经结束的旧 run id。
        if (executions.isActive(run.id())) activeByThread.put(thread, run.id());
        else activeByThread.remove(thread, run.id());
    }

    private void requireIdleThread(String thread) {
        if (pendingRecoveryByThread.containsKey(thread)) {
            throw new IllegalStateException("当前会话正在等待工作流恢复确认");
        }
        String activeId = activeByThread.get(thread);
        if (activeId == null) return;
        if (executions.isActive(activeId)) {
            throw new IllegalStateException("当前会话的工作流仍在运行，请等待结束或先取消");
        }
        activeByThread.remove(thread, activeId);
    }

    private void confirmAndContinueSystem(GraphRun recoverable, String thread) {
        PendingRecovery pending = pendingRecoveryByThread.get(thread);
        if (pending == null || !recoverable.id().equals(pending.runId())) return;
        try {
            var port = ToolConfirmationManager.getPort();
            if (port == null || !port.isAvailable()) {
                removePending(thread, recoverable.id());
                sendError(pending.callbacks(), new IllegalStateException(
                        "检测到待恢复的系统工作流，但当前无法请求恢复确认；本条消息尚未执行"));
                return;
            }
            boolean confirmed = port.confirm(new ConfirmRequest(
                    "workflow_recovery", "恢复上次运行",
                    "上次运行在节点「" + recoverable.currentNodeId()
                            + "」异常中断。"
                            + (pending.recoveryPolicy() == SystemRecoveryPolicy.RESUME_ONLY
                            ? "选择继续会恢复该运行；选择取消会放弃旧运行并重新开始本次恢复。"
                            : "选择继续会先恢复旧运行，再自动处理当前消息；"
                            + "选择取消会放弃旧运行并直接处理当前消息。"),
                    ConfirmKind.CONFIRM, 60, "", false));
            if (!confirmed) {
                PendingRecovery removed = removePending(thread, recoverable.id());
                if (removed == null || removed.cancelled().get()) return;
                executions.cancel(recoverable.id());
                sendEvent(removed.callbacks(), new ConversationEvent.Hint(
                        "已放弃上次未完成的运行，正在处理当前消息"));
                startSystemInvocation(removed.graph(), thread, removed.invocation(),
                        removed.callbacks(), removed.pipeline());
                return;
            }
            synchronized (this) {
                pending = pendingRecoveryByThread.get(thread);
                if (pending == null || pending.cancelled().get()
                        || !recoverable.id().equals(pending.runId())) return;
                try {
                    ConversationCallbacks recoveryCallbacks = mutedRecoveryCallbacks();
                    GraphListener listener = queuedRecoveryBridge(pending, thread);
                    Map<Class<?>, Object> services = systemServices(
                            recoveryCallbacks, pending.pipeline());
                    GraphRun resumed = executions.resume(recoverable.id(), null, true, listener, services);
                    trackActive(thread, resumed);
                } catch (Throwable failure) {
                    pendingRecoveryByThread.remove(thread, pending);
                    sendError(pending.callbacks(), failure);
                }
            }
        } catch (Throwable failure) {
            PendingRecovery removed = removePending(thread, recoverable.id());
            // 取消路径已经发送 CANCELLED 终态；确认线程被中断时不能再发送第二个终态。
            if (removed != null && !removed.cancelled().get()) sendError(removed.callbacks(), failure);
        }
    }

    private GraphRun startSystemInvocation(GraphDefinition graph, String thread, GraphState invocation,
                                           ConversationCallbacks callbacks, SystemPipeline pipeline) {
        GraphState state = checkpoints.loadThreadState(graph.id(), thread)
                .merge(invocation == null ? new GraphState() : invocation);
        GraphRun run = executions.start(graph, thread, state, bridge(callbacks, thread),
                systemServices(callbacks, pipeline));
        trackActive(thread, run);
        return run;
    }

    private Map<Class<?>, Object> systemServices(
            ConversationCallbacks callbacks, SystemPipeline pipeline) {
        return Map.of(AgentRuntime.class, agentRuntime,
                ConversationCallbacks.class, callbacks, SystemPipeline.class, pipeline);
    }

    private GraphListener queuedRecoveryBridge(PendingRecovery pending, String thread) {
        return event -> {
            if (!(event instanceof GraphEvent.RunFinished finished)) {
                sendEvent(pending.callbacks(), new ConversationEvent.Custom("graph_trace", event));
                if (event instanceof GraphEvent.NodeStarted e) {
                    sendEvent(pending.callbacks(), new ConversationEvent.Progress(
                            "graph:recovery:" + e.nodeId(), "恢复 · " + e.label(),
                            ConversationEvent.Progress.Status.RUNNING, null));
                } else if (event instanceof GraphEvent.NodeCompleted e) {
                    sendEvent(pending.callbacks(), new ConversationEvent.Progress(
                            "graph:recovery:" + e.nodeId(), "恢复 · " + e.label(),
                            ConversationEvent.Progress.Status.DONE, null));
                }
                return;
            }
            activeByThread.remove(thread, finished.runId());
            if (!pendingRecoveryByThread.remove(thread, pending) || pending.cancelled().get()) return;
            sendEvent(pending.callbacks(), new ConversationEvent.Custom("graph_trace", event));
            if (pending.recoveryPolicy() == SystemRecoveryPolicy.RESUME_ONLY) {
                if (finished.status() == RunStatus.FAILED) {
                    sendError(pending.callbacks(), new IllegalStateException(finished.error()));
                } else {
                    sendComplete(pending.callbacks());
                }
                return;
            }
            sendEvent(pending.callbacks(), new ConversationEvent.Hint(
                    finished.status() == RunStatus.COMPLETED
                            ? "上次运行已恢复完成，正在处理当前消息"
                            : "上次运行未能恢复完成，仍将继续处理当前消息"));
            try {
                startSystemInvocation(pending.graph(), thread, pending.invocation(),
                        pending.callbacks(), pending.pipeline());
            } catch (Throwable failure) {
                sendError(pending.callbacks(), failure);
            }
        };
    }

    private static void sendEvent(ConversationCallbacks callbacks, ConversationEvent event) {
        try { callbacks.onEvent(event); }
        catch (Throwable ignored) { }
    }

    private static void sendComplete(ConversationCallbacks callbacks) {
        try { callbacks.onComplete(); }
        catch (Throwable ignored) { }
    }

    private static void sendError(ConversationCallbacks callbacks, Throwable failure) {
        try { callbacks.onError(failure); }
        catch (Throwable ignored) { }
    }

    private static ConversationCallbacks mutedRecoveryCallbacks() {
        return new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) { }
            @Override public void onComplete() { }
            @Override public void onError(Throwable error) { }
        };
    }

    private PendingRecovery removePending(String thread, String runId) {
        PendingRecovery pending = pendingRecoveryByThread.get(thread);
        if (pending == null || !runId.equals(pending.runId())) return null;
        return pendingRecoveryByThread.remove(thread, pending) ? pending : null;
    }

    private boolean cancelPendingRecovery(PendingRecovery pending) {
        pending.cancelled().set(true);
        pending.confirmationThread().interrupt();
        executions.cancel(pending.runId());
        if (pending.terminalSent().compareAndSet(false, true)) {
            sendComplete(pending.callbacks());
        }
        return true;
    }

    private String threadId(String sessionId, String workflowId) {
        String session = sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId;
        return workspaceId + ":" + session + ":" + workflowId;
    }

    @Override public void close() {
        for (Map.Entry<String, PendingRecovery> entry : pendingRecoveryByThread.entrySet()) {
            PendingRecovery pending = entry.getValue();
            if (pendingRecoveryByThread.remove(entry.getKey(), pending)) cancelPendingRecovery(pending);
        }
        executions.close();
    }

    private record PendingRecovery(
            String runId,
            Thread confirmationThread,
            GraphDefinition graph,
            GraphState invocation,
            ConversationCallbacks callbacks,
            SystemPipeline pipeline,
            SystemRecoveryPolicy recoveryPolicy,
            AtomicBoolean cancelled,
            AtomicBoolean terminalSent) {
        private PendingRecovery(
                String runId, Thread confirmationThread, GraphDefinition graph, GraphState invocation,
                ConversationCallbacks callbacks, SystemPipeline pipeline,
                SystemRecoveryPolicy recoveryPolicy) {
            this(runId, confirmationThread, graph, invocation, callbacks, pipeline, recoveryPolicy,
                    new AtomicBoolean(), new AtomicBoolean());
        }
    }
}
