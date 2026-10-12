package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.RunOutcome;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.TurnPausedException;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Continues an already-authorized caller's durable child without restarting successful work. */
public final class ChildTurnContinuation {
    private ChildTurnContinuation() { }

    public static RunHandle start(AgentClient agents, RunRequest request) {
        var previous = agents.supportsManagedTurns() ? agents.activeTurn(request.scope()).orElse(null) : null;
        if (previous != null && previous.state() == RunState.PAUSED) {
            return resume(agents, previous);
        }
        RunHandle handle = agents.start(request);
        if (agents.supportsManagedTurns() && !handle.completion().toCompletableFuture().isDone()) {
            var snapshot = agents.get(handle.id());
            if (snapshot.state() == RunState.PAUSED) {
                handle = resume(agents, snapshot);
            }
        }
        return handle;
    }

    private static RunHandle resume(AgentClient agents, com.javaclaw.framework.api.RunSnapshot snapshot) {
        if (snapshot.output() != null && snapshot.output().path("kind")
                .asText().equals("tool.recovery_required")) {
            throw new TurnPausedException("子轮次 " + snapshot.id() + " 的工具结果不明，需核对后恢复");
        }
        return agents.resume(snapshot.id(), new ResumeCommand("delegation.continue",
                JsonNodeFactory.instance.objectNode()));
    }

    public static RunOutcome await(AgentClient agents, RunHandle handle, Duration timeout,
                                   Runnable cancellationCheck) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        var completion = handle.completion().toCompletableFuture();
        while (true) {
            cancellationCheck.run();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("子轮次等待超时: " + handle.id());
            try {
                return completion.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                        TimeUnit.NANOSECONDS);
            } catch (TimeoutException pending) {
                if (agents.supportsManagedTurns() && agents.get(handle.id()).state() == RunState.PAUSED) {
                    throw new TurnPausedException("子轮次 " + handle.id() + " 已暂停，请核对执行结果后恢复");
                }
            }
        }
    }
}
