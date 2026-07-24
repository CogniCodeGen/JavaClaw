package com.javaclaw.workflow.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 节点执行器注册表；重复类型拒绝覆盖。 */
public final class NodeExecutorRegistry {
    private final Map<String, NodeExecutor> executors = new LinkedHashMap<>();

    public synchronized void register(NodeExecutor executor) {
        if (executor == null || executor.type() == null || executor.type().isBlank()) {
            throw new IllegalArgumentException("executor/type 不能为空");
        }
        if (executors.putIfAbsent(executor.type(), executor) != null) {
            throw new IllegalStateException("节点执行器重复: " + executor.type());
        }
    }

    public synchronized Optional<NodeExecutor> find(String type) {
        return Optional.ofNullable(executors.get(type));
    }

    public synchronized NodeExecutor require(String type) {
        return find(type).orElseThrow(() -> new IllegalStateException("未知节点执行器: " + type));
    }

    public synchronized Map<String, NodeExecutor> snapshot() { return Map.copyOf(executors); }
}
