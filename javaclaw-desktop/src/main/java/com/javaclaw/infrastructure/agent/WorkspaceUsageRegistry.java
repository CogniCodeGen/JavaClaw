package com.javaclaw.infrastructure.agent;

import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.RunUsageObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Bridges the process-wide usage ledger to currently open workspace projections. */
public final class WorkspaceUsageRegistry implements RunUsageObserver {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceUsageRegistry.class);
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<RunUsageObserver>> observers =
            new ConcurrentHashMap<>();

    public Registration register(String workspaceId, RunUsageObserver observer) {
        String key = Objects.requireNonNull(workspaceId, "workspaceId");
        RunUsageObserver value = Objects.requireNonNull(observer, "observer");
        observers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(value);
        return () -> {
            CopyOnWriteArrayList<RunUsageObserver> registered = observers.get(key);
            if (registered == null) return;
            registered.remove(value);
            if (registered.isEmpty()) observers.remove(key, registered);
        };
    }

    @Override
    public void recorded(
            RunId runId, RunScope scope, long inputTokens,
            long outputTokens, BigDecimal cost) {
        for (RunUsageObserver observer : observers.getOrDefault(
                scope.workspaceId(), new CopyOnWriteArrayList<>())) {
            try {
                observer.recorded(runId, scope, inputTokens, outputTokens, cost);
            } catch (RuntimeException failure) {
                log.warn("工作区用量投影失败: workspace={}, run={}",
                        scope.workspaceId(), runId, failure);
            }
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override void close();
    }
}
