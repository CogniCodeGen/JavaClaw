package com.javaclaw.workflow.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

/** 节点执行器注册表；重复类型拒绝覆盖。 */
public final class NodeExecutorRegistry {
    private final Map<String, NodeExecutor> executors = new LinkedHashMap<>();
    private final List<Resolver> resolvers = new ArrayList<>();

    @FunctionalInterface
    public interface Resolver {
        Optional<NodeExecutor> resolve(String type);
    }

    public synchronized void register(NodeExecutor executor) {
        if (executor == null || executor.type() == null || executor.type().isBlank()) {
            throw new IllegalArgumentException("executor/type 不能为空");
        }
        if (executors.putIfAbsent(executor.type(), executor) != null) {
            throw new IllegalStateException("节点执行器重复: " + executor.type());
        }
    }

    public synchronized Optional<NodeExecutor> find(String type) {
        NodeExecutor direct = executors.get(type);
        if (direct != null) return Optional.of(direct);
        for (Resolver resolver : resolvers) {
            Optional<NodeExecutor> resolved = resolver.resolve(type);
            if (resolved.isPresent()) return resolved;
        }
        return Optional.empty();
    }

    /** Adds a discovery-only fallback, used for newly installed extension node types. */
    public synchronized void registerResolver(Resolver resolver) {
        if (resolver == null) throw new IllegalArgumentException("resolver 不能为空");
        resolvers.add(resolver);
    }

    /** Runtime copy containing built-ins plus only the exact extension plan captured at start. */
    public synchronized NodeExecutorRegistry fixedOverlay(Resolver exactResolver) {
        NodeExecutorRegistry result = new NodeExecutorRegistry();
        executors.values().forEach(result::register);
        result.registerResolver(exactResolver);
        return result;
    }

    public synchronized NodeExecutor require(String type) {
        return find(type).orElseThrow(() -> new IllegalStateException("未知节点执行器: " + type));
    }

    public synchronized Map<String, NodeExecutor> snapshot() { return Map.copyOf(executors); }
}
