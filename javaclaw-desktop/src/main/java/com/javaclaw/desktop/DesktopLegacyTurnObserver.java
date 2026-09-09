package com.javaclaw.desktop;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.DesktopState;

/**
 * 仅为未协商公开流的旧服务端保留轮询；导航代次失效后停止读取，所有迟到结果由 owner 再次核验归属。
 *
 * <p>先读取 Turn 再读取正文，避免终态提交发生在正文查询之后。终态的全部剩余页在后台有界归并完成后一次发布， 不能提前清除 activeTurn 或让用户在最后消息尚未到齐时再次发送。
 */
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
        long cursor = store.state().transcript().nextSequence();
        while (!closed.getAsBoolean()) {
            Thread.sleep(150);
            if (closed.getAsBoolean()) {
                return;
            }
            AgentTurn turn = client.turns().read(initial.id());
            if (!turn.id().equals(initial.id()) || !turn.threadId().equals(thread.id())) {
                throw new IllegalStateException("读取的 Turn 不属于目标会话");
            }
            if (closed.getAsBoolean()) {
                return;
            }
            boolean terminal = DesktopStateProjection.terminal(turn.status());
            var page = terminal
                    ? DesktopLegacyHistory.tail(client, thread, cursor, closed)
                    : client.items().list(thread.id(), cursor, 100);
            if (!terminal) {
                DesktopLegacyHistory.requireProgress(page, cursor);
            }
            if (closed.getAsBoolean()) {
                return;
            }
            var approvals = client.approvals().list(Optional.of(turn.id()), false);
            if (closed.getAsBoolean()) {
                return;
            }
            update.accept(state -> DesktopStateProjection.observation(state, turn, page, approvals));
            cursor = page.nextSequence();
            if (terminal) {
                return;
            }
        }
    }
}
