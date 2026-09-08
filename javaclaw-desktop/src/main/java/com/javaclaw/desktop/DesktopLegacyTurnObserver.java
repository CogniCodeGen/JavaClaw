package com.javaclaw.desktop;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.DesktopState;

/** 仅为未协商公开流的旧服务端保留轮询；导航代次失效后停止读取，所有迟到结果由 owner 再次核验归属。 */
final class DesktopLegacyTurnObserver {
    private DesktopLegacyTurnObserver() {}

    static void observe(
            JavaClawClient client,
            ConversationThread thread,
            AgentTurn initial,
            DesktopStore store,
            Consumer<UnaryOperator<DesktopState>> update,
            BooleanSupplier closed)
            throws InterruptedException {
        AgentTurn turn = initial;
        long cursor = store.state().transcript().nextSequence();
        while (!DesktopStateProjection.terminal(turn.status()) && !closed.getAsBoolean()) {
            Thread.sleep(150);
            if (closed.getAsBoolean()) {
                return;
            }
            var page = client.items().list(thread.id(), cursor, 100);
            cursor = page.nextSequence();
            turn = client.turns().read(turn.id());
            var approvals = client.approvals().list(Optional.of(turn.id()), false);
            AgentTurn observed = turn;
            update.accept(state -> DesktopStateProjection.observation(state, observed, page, approvals));
        }
        if (!closed.getAsBoolean()) {
            update.accept(state -> DesktopStateProjection.busy(state, false));
        }
    }
}
