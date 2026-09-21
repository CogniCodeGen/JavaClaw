package com.javaclaw.application.agent;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ToolContext;
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Routes child approvals through the same explicit user interaction as ordinary Agent turns. */
public final class SubAgentApprovalObserver implements BiConsumer<ToolContext, RunHandle>, AutoCloseable {
    private final Supplier<AgentClient> agents;
    private final Executor executor;
    private final Map<RunId, Disposable> subscriptions = new ConcurrentHashMap<>();
    private boolean closed;

    public SubAgentApprovalObserver(Supplier<AgentClient> agents, Executor executor) {
        this.agents = agents;
        this.executor = executor;
    }

    @Override public synchronized void accept(ToolContext parent, RunHandle handle) {
        if (closed || handle.completion().toCompletableFuture().isDone() || subscriptions.containsKey(handle.id())) return;
        ToolCallOrigin origin = origin(parent);
        var snapshot = agents.get().get(handle.id());
        long after = snapshot.state() == com.javaclaw.framework.api.RunState.WAITING_APPROVAL
                ? Math.max(0, snapshot.lastSequence() - 1) : snapshot.lastSequence();
        Disposable.Swap subscription = reactor.core.Disposables.swap();
        subscriptions.put(handle.id(), subscription);
        try {
            subscription.update(handle.events(after).subscribe(event -> {
                if (event.type().equals("core.run.waiting_approval")) {
                    executor.execute(() -> {
                        if (!subscriptions.containsKey(handle.id())
                                || handle.completion().toCompletableFuture().isDone()) return;
                        FrameworkToolApprovalCoordinator.resolve(agents.get(), handle, origin, event.payload());
                    });
                }
            }));
        } catch (RuntimeException failure) {
            subscriptions.remove(handle.id(), subscription);
            subscription.dispose();
            throw failure;
        }
        handle.completion().whenComplete((outcome, failure) -> {
            Disposable previous = subscriptions.remove(handle.id());
            if (previous != null) previous.dispose();
        });
    }

    static ToolCallOrigin origin(ToolContext parent) {
        String kind = parent.request().source().kind();
        if (kind.equals("chat") || kind.equals("plan")) return ToolCallOrigin.INTERACTIVE;
        String task = parent.request().source().id();
        if (kind.equals("schedule")) return ToolCallOrigin.scheduled(task);
        if (kind.equals("loop") || kind.equals("sdd") || kind.equals("workflow")) {
            String directory = parent.request().attributes().getOrDefault("workDir",
                    com.fasterxml.jackson.databind.node.TextNode.valueOf("")).asText();
            return ToolCallOrigin.managedTask(task, directory);
        }
        return ToolCallOrigin.UNKNOWN;
    }

    @Override public synchronized void close() {
        closed = true;
        subscriptions.values().forEach(Disposable::dispose);
        subscriptions.clear();
    }
}
