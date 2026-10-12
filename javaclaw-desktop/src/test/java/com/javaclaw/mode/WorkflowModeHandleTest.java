package com.javaclaw.mode;

import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.TerminalCallbackGuard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowModeHandleTest {

    @Test
    void eachHandleCancelsOnlyItsCapturedRunAndOldTerminalDoesNotAffectNewRun() {
        List<String> cancelledRuns = new ArrayList<>();
        var callbacksA = new TerminalCallbackGuard(noopCallbacks());
        var callbacksB = new TerminalCallbackGuard(noopCallbacks());
        var handleA = WorkflowMode.createRunHandle(
                callbacksA, "workflow", "session-a",
                (workflowId, sessionId) -> cancelledRuns.add(workflowId + "/" + sessionId));
        var handleB = WorkflowMode.createRunHandle(
                callbacksB, "workflow", "session-b",
                (workflowId, sessionId) -> cancelledRuns.add(workflowId + "/" + sessionId));

        callbacksA.onTerminal(ConversationOutcome.completed());

        assertTrue(handleA.isTerminal());
        assertFalse(handleA.cancel(CancellationReason.USER_REQUEST));
        assertFalse(handleB.isTerminal());
        assertTrue(handleB.cancel(CancellationReason.SESSION_SWITCH));
        assertEquals(List.of("workflow/session-b"), cancelledRuns);
    }

    @Test
    void concurrentHandlesKeepTheirOwnSessionIdentity() {
        List<String> cancelledRuns = new ArrayList<>();
        var handleA = WorkflowMode.createRunHandle(
                new TerminalCallbackGuard(noopCallbacks()), "workflow-a", "session-a",
                (workflowId, sessionId) -> cancelledRuns.add(workflowId + "/" + sessionId));
        var handleB = WorkflowMode.createRunHandle(
                new TerminalCallbackGuard(noopCallbacks()), "workflow-b", "session-b",
                (workflowId, sessionId) -> cancelledRuns.add(workflowId + "/" + sessionId));

        assertTrue(handleA.cancel(CancellationReason.USER_REQUEST));
        assertTrue(handleB.cancel(CancellationReason.USER_REQUEST));
        assertEquals(List.of("workflow-a/session-a", "workflow-b/session-b"), cancelledRuns);
    }

    private static ConversationCallbacks noopCallbacks() {
        return new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {}

            @Override
            public void onTerminal(ConversationOutcome outcome) {}
        };
    }
}
