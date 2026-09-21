package com.javaclaw.application.agent;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.loop.agent.FrameworkLoopRunner;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.schedule.FrameworkScheduledTaskRunner;
import com.javaclaw.schedule.ScheduledRunControl;
import com.javaclaw.support.CapturingLifecycleClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntryThreadMappingTest {
    @TempDir Path directory;
    private final List<ConversationOutcome> outcomes = new ArrayList<>();
    private final ConversationCallbacks callbacks = new ConversationCallbacks() {
        @Override public void onEvent(ConversationEvent event) { }
        @Override public void onTerminal(ConversationOutcome outcome) { outcomes.add(outcome); }
    };

    @Test void scheduleUsesOneThreadAndDistinctIdempotentTurnsAcrossTriggers() {
        var agents = new CapturingLifecycleClient();
        var runner = new FrameworkScheduledTaskRunner(agents, workspace(), Runnable::run);
        runner.run(new ScheduledRunControl("daily"), ToolCallOrigin.SCHEDULED, "first", callbacks);
        runner.run(new ScheduledRunControl("daily"), ToolCallOrigin.SCHEDULED, "second", callbacks);
        runner.run(new ScheduledRunControl("other"), ToolCallOrigin.SCHEDULED, "third", callbacks);
        assertEquals(3, outcomes.size());
        assertTrue(outcomes.stream().allMatch(ConversationOutcome.Completed.class::isInstance));
        assertEquals(agents.requests.get(0).scope(), agents.requests.get(1).scope());
        assertNotEquals(agents.requests.get(0).scope(), agents.requests.get(2).scope());
        assertNotEquals(agents.requests.get(0).idempotencyKey(), agents.requests.get(1).idempotencyKey());
        runner.shutdown();
    }

    @Test void loopIterationsReuseAChildThreadAndKeepCriticOwnerAliveUntilCoordinationEnds() {
        var agents = new CapturingLifecycleClient();
        try (var runner = new FrameworkLoopRunner(agents, workspace(), "chat-parent", "loop-a",
                "目标", null, 30)) {
            runner.runOnce("iteration-one", callbacks);
            runner.runOnce("iteration-two", callbacks);
            assertEquals(1, agents.coordinators.size());
            assertEquals(2, agents.requests.size());
            assertEquals(agents.requests.getFirst().scope(), agents.requests.getLast().scope());
            assertNotEquals("chat-parent", agents.requests.getFirst().scope().sessionId());
            assertEquals(runner.ownerRunId(), agents.requests.getFirst().linkage().parentRunId());
            var owner = agents.turns.values().iterator().next();
            assertFalse(owner.done.isDone(), "critic must remain owned by the running coordinator");
            runner.finish(ConversationOutcome.completed());
            assertTrue(owner.done.join().successful());
        }
    }

    private WorkspaceContext workspace() {
        return new WorkspaceContext("workspace", directory, directory, directory, directory, directory);
    }
}
