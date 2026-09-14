package com.javaclaw.desktop.shell;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.InputInteractionState;
import com.javaclaw.desktop.state.InteractionState;
import com.javaclaw.desktop.state.ThreadState;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellRenderChangesTest {
    private final Workspace workspace = DesktopTestFixtures.workspace();
    private final ConversationThread thread = DesktopTestFixtures.thread(workspace);

    @Test
    void 活动Turn变化同时刷新正文标签和操作区() {
        DesktopState previous = state(Optional.empty(), InteractionState.initial());
        AgentTurn running = DesktopTestFixtures.turn(thread, TurnStatus.RUNNING, 1);

        ShellRenderChanges changes =
                ShellRenderChanges.between(previous, state(Optional.of(running), InteractionState.initial()));

        assertTrue(changes.transcript());
        assertTrue(changes.labels());
        assertTrue(changes.actions());
        assertFalse(changes.scope());
        assertFalse(changes.connection());
        assertFalse(changes.inputs());
    }

    @Test
    void 审批和输入请求变化会刷新活动正文而普通忙碌变化不会扩大正文重绘() {
        DesktopState baseline = state(Optional.empty(), InteractionState.initial());
        ShellRenderChanges approval = ShellRenderChanges.between(baseline, state(Optional.empty(), approval()));
        ShellRenderChanges input = ShellRenderChanges.between(baseline, state(Optional.empty(), input()));
        ShellRenderChanges busy = ShellRenderChanges.between(baseline, state(Optional.empty(), busy()));

        assertTrue(approval.transcript());
        assertTrue(approval.labels());
        assertFalse(approval.inputs());
        assertTrue(input.transcript());
        assertTrue(input.labels());
        assertTrue(input.inputs());
        assertFalse(busy.transcript());
        assertFalse(busy.labels());
        assertTrue(busy.actions());
    }

    private DesktopState state(Optional<AgentTurn> activeTurn, InteractionState interaction) {
        DesktopState base = DesktopState.initial();
        return new DesktopState(
                base.connection(),
                base.navigation(),
                new ThreadState(
                        List.of(workspace), Optional.of(workspace), List.of(thread), Optional.of(thread), activeTurn),
                base.transcript(),
                interaction);
    }

    private static InteractionState approval() {
        return new InteractionState(
                List.of(),
                Optional.empty(),
                List.of(DesktopTestFixtures.approval(ApprovalState.PENDING)),
                InputInteractionState.initial(),
                false,
                Optional.empty());
    }

    private static InteractionState input() {
        return new InteractionState(
                List.of(),
                Optional.empty(),
                List.of(),
                new InputInteractionState(List.of(DesktopTestFixtures.input()), Optional.empty(), Optional.empty(), 1),
                false,
                Optional.empty());
    }

    private static InteractionState busy() {
        return new InteractionState(
                List.of(), Optional.empty(), List.of(), InputInteractionState.initial(), true, Optional.empty());
    }
}
