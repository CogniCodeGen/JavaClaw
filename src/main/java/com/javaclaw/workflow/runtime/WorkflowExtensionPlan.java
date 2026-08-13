package com.javaclaw.workflow.runtime;

import com.javaclaw.framework.spi.ExtensionLock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact extension-node bindings retained for the lifetime of one workflow run. */
public final class WorkflowExtensionPlan implements AutoCloseable {
    private static final WorkflowExtensionPlan EMPTY = new WorkflowExtensionPlan(
            0, List.of(), Map.of(), () -> { });

    private final long generation;
    private final List<ExtensionLock> locks;
    private final Map<String, NodeExecutor> executors;
    private final AutoCloseable lease;
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorkflowExtensionPlan(
            long generation,
            List<ExtensionLock> locks,
            Map<String, NodeExecutor> executors,
            AutoCloseable lease) {
        this.generation = generation;
        this.locks = List.copyOf(locks == null ? List.of() : locks);
        this.executors = Map.copyOf(executors == null ? Map.of() : executors);
        this.lease = lease == null ? () -> { } : lease;
    }

    public static WorkflowExtensionPlan empty() { return EMPTY; }

    public long generation() { return generation; }
    public List<ExtensionLock> locks() { return locks; }
    public Optional<NodeExecutor> find(String type) {
        return Optional.ofNullable(executors.get(type));
    }
    public Map<String, NodeExecutor> executors() { return executors; }

    @Override
    public void close() {
        if (this == EMPTY || !closed.compareAndSet(false, true)) return;
        try {
            lease.close();
        } catch (Exception failure) {
            throw new IllegalStateException("failed to release workflow extension plan", failure);
        }
    }
}
