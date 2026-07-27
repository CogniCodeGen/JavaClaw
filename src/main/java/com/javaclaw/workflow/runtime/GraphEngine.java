package com.javaclaw.workflow.runtime;

import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.store.GraphCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 顺序、有界、可检查点的确定性图执行器。 */
public final class GraphEngine {
    private static final Logger log = LoggerFactory.getLogger(GraphEngine.class);

    private final NodeExecutorRegistry registry;
    private final GraphCheckpointStore store;

    public GraphEngine(NodeExecutorRegistry registry, GraphCheckpointStore store) {
        this.registry = registry;
        this.store = store;
    }

    public GraphRun execute(GraphRun run, CancellationToken cancellation,
                            GraphListener listener, Map<Class<?>, Object> services) {
        GraphListener sink = listener == null ? GraphListener.NOOP : listener;
        GraphDefinition graph = run.definition();
        try {
            GraphValidator.requireValid(graph, registry);
            Map<String, NodeDefinition> nodes = new HashMap<>();
            graph.nodes().forEach(n -> nodes.put(n.id(), n));

            emit(sink, new GraphEvent.RunStarted(run.id(), run.workflowId(), run.threadId()));

            while (true) {
                cancellation.throwIfCancelled();
                if (cancellation.isPauseRequested()) return pause(run, sink);
                if (run.stepCount() >= graph.maxSteps()) {
                    throw new IllegalStateException("工作流达到最大步数限制: " + graph.maxSteps());
                }

                String nodeId = run.nextNodeId();
                NodeDefinition node = nodes.get(nodeId);
                if (node == null) throw new IllegalStateException("待执行节点不存在: " + nodeId);
                run.currentNodeId(nodeId);
                run.nextNodeId(null);
                store.checkpoint(run, nodeId, CheckpointPhase.BEFORE_NODE);
                emit(sink, new GraphEvent.NodeStarted(run.id(), node.id(), node.label(), run.stepCount() + 1));

                NodeResult result;
                try {
                    result = executeWithRetry(run, node, cancellation, sink, services);
                } catch (GraphCancelledException cancelled) {
                    throw cancelled;
                } catch (Exception failure) {
                    cancellation.throwIfCancelled();
                    EdgeDefinition errorEdge = graph.edges().stream()
                            .filter(e -> e.source().equals(nodeId) && e.kind() == EdgeKind.ERROR)
                            .min(Comparator.comparingInt(EdgeDefinition::priority)).orElse(null);
                    if (errorEdge == null) throw failure;
                    run.state(run.state().apply(StatePatch.builder()
                            .set("_error.nodeId", nodeId)
                            .set("_error.message", message(failure)).build()));
                    run.error(message(failure));
                    run.stepCount(run.stepCount() + 1);
                    run.nextNodeId(errorEdge.target());
                    store.saveThreadState(run.workflowId(), run.threadId(), run.state());
                    store.checkpoint(run, nodeId, CheckpointPhase.AFTER_NODE);
                    emit(sink, new GraphEvent.Transition(run.id(), nodeId, errorEdge.target(), errorEdge.id()));
                    continue;
                }

                cancellation.throwIfCancelled();
                run.state(run.state().apply(result.patch()));
                if (result.output() != null) run.output(result.output());
                run.stepCount(run.stepCount() + 1);

                if (result.interrupt() != null) {
                    run.interrupt(result.interrupt());
                    run.status(RunStatus.WAITING_INPUT);
                    run.nextNodeId(nodeId);
                    store.saveThreadState(run.workflowId(), run.threadId(), run.state());
                    store.checkpoint(run, nodeId, CheckpointPhase.INTERRUPT);
                    emit(sink, new GraphEvent.Interrupted(run.id(), nodeId, result.interrupt().prompt()));
                    emit(sink, new GraphEvent.RunFinished(run.id(), run.status(), run.output(), null));
                    return run;
                }

                emit(sink, new GraphEvent.NodeCompleted(run.id(), node.id(), node.label(), run.stepCount()));
                if (node.type() == NodeType.END) return complete(run, sink);

                EdgeDefinition edge = selectSuccessEdge(graph, nodeId, run);
                if (edge == null) throw new IllegalStateException("节点没有可用出口: " + nodeId);
                run.nextNodeId(edge.target());
                run.interrupt(null);
                store.saveThreadState(run.workflowId(), run.threadId(), run.state());
                store.checkpoint(run, nodeId, CheckpointPhase.AFTER_NODE);
                emit(sink, new GraphEvent.Transition(run.id(), nodeId, edge.target(), edge.id()));

                if (cancellation.isPauseRequested()) return pause(run, sink);
            }
        } catch (GraphCancelledException e) {
            run.status(RunStatus.CANCELLED);
            run.error(null);
            safeTerminalCheckpoint(run);
            emit(sink, new GraphEvent.RunFinished(run.id(), run.status(), run.output(), null));
            return run;
        } catch (Throwable failure) {
            run.status(RunStatus.FAILED);
            run.error(message(failure));
            safeTerminalCheckpoint(run);
            emit(sink, new GraphEvent.RunFinished(run.id(), run.status(), run.output(), run.error()));
            log.error("工作流执行失败 run={} node={}", run.id(), run.currentNodeId(), failure);
            return run;
        }
    }

    private NodeResult executeWithRetry(GraphRun run, NodeDefinition node,
                                        CancellationToken cancellation, GraphListener listener,
                                        Map<Class<?>, Object> services) throws Exception {
        NodeExecutor executor = registry.require(node.executorType());
        Exception last = null;
        for (int attempt = 1; attempt <= node.retryPolicy().maxAttempts(); attempt++) {
            cancellation.throwIfCancelled();
            long backoff = node.retryPolicy().backoffBeforeAttempt(attempt);
            if (backoff > 0) awaitBackoff(backoff, cancellation);
            try {
                NodeExecutionContext context = new NodeExecutionContext(run.id(), run.threadId(), node,
                        run.state(), cancellation, listener, services);
                NodeResult result = executor.execute(context);
                cancellation.throwIfCancelled();
                return result == null ? NodeResult.next() : result;
            } catch (GraphCancelledException e) {
                throw e;
            } catch (Exception e) {
                cancellation.throwIfCancelled();
                last = e;
                if (attempt < node.retryPolicy().maxAttempts()) {
                    emit(listener, new GraphEvent.NodeRetry(run.id(), node.id(), attempt + 1, message(e)));
                }
            }
        }
        throw last == null ? new IllegalStateException("节点执行失败") : last;
    }

    private static void awaitBackoff(long millis, CancellationToken cancellation) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            cancellation.throwIfCancelled();
            long remainingMillis = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            try {
                Thread.sleep(Math.min(remainingMillis, 100L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GraphCancelledException();
            }
        }
    }

    private EdgeDefinition selectSuccessEdge(GraphDefinition graph, String nodeId, GraphRun run) {
        List<EdgeDefinition> conditional = graph.edges().stream()
                .filter(e -> e.source().equals(nodeId) && e.kind() == EdgeKind.CONDITIONAL)
                .sorted(Comparator.comparingInt(EdgeDefinition::priority)).toList();
        if (!conditional.isEmpty()) {
            EdgeDefinition fallback = null;
            for (EdgeDefinition edge : conditional) {
                if (edge.defaultEdge()) fallback = edge;
                else if (ConditionEvaluator.matches(run.state(), edge.condition())) return edge;
            }
            return fallback;
        }
        return graph.edges().stream()
                .filter(e -> e.source().equals(nodeId) && e.kind() == EdgeKind.NORMAL)
                .findFirst().orElse(null);
    }

    private GraphRun pause(GraphRun run, GraphListener sink) {
        run.status(RunStatus.PAUSED);
        store.checkpoint(run, run.currentNodeId(), CheckpointPhase.PAUSE);
        emit(sink, new GraphEvent.RunFinished(run.id(), run.status(), run.output(), null));
        return run;
    }

    private GraphRun complete(GraphRun run, GraphListener sink) {
        run.status(RunStatus.COMPLETED);
        run.nextNodeId(null);
        run.error(null);
        store.saveThreadState(run.workflowId(), run.threadId(), run.state());
        store.checkpoint(run, run.currentNodeId(), CheckpointPhase.TERMINAL);
        emit(sink, new GraphEvent.RunFinished(run.id(), run.status(), run.output(), null));
        return run;
    }

    private void safeTerminalCheckpoint(GraphRun run) {
        try { store.checkpoint(run, run.currentNodeId(), CheckpointPhase.TERMINAL); }
        catch (Throwable persistFailure) { log.error("工作流终态检查点失败 run={}", run.id(), persistFailure); }
    }

    private static void emit(GraphListener listener, GraphEvent event) {
        try { listener.onEvent(event); }
        catch (Throwable ignored) { }
    }

    private static String message(Throwable t) {
        String value = t.getMessage();
        return value == null || value.isBlank() ? t.getClass().getSimpleName() : value;
    }
}
