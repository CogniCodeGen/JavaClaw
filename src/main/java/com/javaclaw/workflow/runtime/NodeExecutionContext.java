package com.javaclaw.workflow.runtime;

import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.NodeDefinition;

import java.util.Map;
import java.util.Objects;

/** 节点运行期上下文。services 只存在内存中，不进入 GraphState/checkpoint。 */
public final class NodeExecutionContext {
    private final String runId;
    private final String threadId;
    private final NodeDefinition node;
    private final GraphState state;
    private final CancellationToken cancellation;
    private final GraphListener listener;
    private final Map<Class<?>, Object> services;

    public NodeExecutionContext(String runId, String threadId, NodeDefinition node,
                                GraphState state, CancellationToken cancellation,
                                GraphListener listener, Map<Class<?>, Object> services) {
        this.runId = runId;
        this.threadId = threadId;
        this.node = Objects.requireNonNull(node);
        this.state = Objects.requireNonNull(state);
        this.cancellation = Objects.requireNonNull(cancellation);
        this.listener = listener == null ? GraphListener.NOOP : listener;
        this.services = services == null ? Map.of() : Map.copyOf(services);
    }

    public String runId() { return runId; }
    public String threadId() { return threadId; }
    public NodeDefinition node() { return node; }
    public GraphState state() { return state; }
    public CancellationToken cancellation() { return cancellation; }
    public GraphListener listener() { return listener; }

    public <T> T require(Class<T> type) {
        Object service = services.get(type);
        if (service == null) throw new IllegalStateException("节点缺少运行期服务: " + type.getName());
        return type.cast(service);
    }

    public <T> T optional(Class<T> type) {
        Object service = services.get(type);
        return service == null ? null : type.cast(service);
    }
}
