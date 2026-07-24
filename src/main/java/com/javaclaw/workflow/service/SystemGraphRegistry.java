package com.javaclaw.workflow.service;

import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 代码内置只读系统图注册表。 */
public final class SystemGraphRegistry {
    private final Map<String, GraphDefinition> graphs = new LinkedHashMap<>();

    public synchronized void register(GraphDefinition graph) {
        if (graph.kind() != GraphKind.SYSTEM) throw new IllegalArgumentException("只允许注册系统图");
        GraphDefinition existing = graphs.putIfAbsent(graph.id(), graph);
        if (existing != null && !existing.equals(graph)) throw new IllegalStateException("系统图冲突: " + graph.id());
    }

    public synchronized GraphDefinition get(String id) { return graphs.get(id); }
    public synchronized List<GraphDefinition> list() { return List.copyOf(graphs.values()); }
}
