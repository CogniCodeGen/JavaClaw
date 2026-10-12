package com.javaclaw.api.conversation;

import java.util.Objects;
import java.util.function.Function;

/**
 * 把任意底层完成、失败和取消竞争收敛为一个终态。
 *
 * <p>终态之后的迟到事件会被丢弃，避免旧运行污染下一轮消息。</p>
 */
public final class TerminalCallbackGuard implements ConversationCallbacks {

    private final ConversationCallbacks delegate;
    private final Object lifecycleLock = new Object();
    private State state = State.RUNNING;
    private int inFlightEvents;
    private boolean terminalDelivered;
    private ConversationOutcome pendingOutcome;
    private ConversationOutcome competingOutcome;

    private enum State {
        RUNNING,
        CANCELLING,
        TERMINAL
    }

    public TerminalCallbackGuard(ConversationCallbacks delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onEvent(ConversationEvent event) {
        synchronized (lifecycleLock) {
            if (state == State.TERMINAL) return;
            inFlightEvents++;
        }
        try {
            delegate.onEvent(event);
        } finally {
            finishEvent();
        }
    }

    @Override
    public void onTerminal(ConversationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        ConversationOutcome delivery = null;
        synchronized (lifecycleLock) {
            if (state == State.RUNNING) {
                state = State.TERMINAL;
                pendingOutcome = outcome;
                delivery = claimTerminalDelivery();
            } else if (state == State.CANCELLING && competingOutcome == null) {
                competingOutcome = outcome;
            }
        }
        deliver(delivery);
    }

    private void finishEvent() {
        ConversationOutcome delivery;
        synchronized (lifecycleLock) {
            inFlightEvents--;
            delivery = claimTerminalDelivery();
        }
        deliver(delivery);
    }

    /**
     * 原子接管一次取消。取消动作执行期间到达的底层终态会暂存；动作接受时取消结果优先，
     * 动作拒绝时再转发已到达的底层结果。
     */
    boolean cancel(CancellationReason reason,
                   Function<CancellationReason, Boolean> cancelAction) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(cancelAction, "cancelAction");
        synchronized (lifecycleLock) {
            if (state != State.RUNNING) return false;
            state = State.CANCELLING;
        }

        final boolean accepted;
        try {
            accepted = Boolean.TRUE.equals(cancelAction.apply(reason));
        } catch (RuntimeException | Error failure) {
            ConversationOutcome delivery;
            synchronized (lifecycleLock) {
                if (competingOutcome == null) {
                    state = State.RUNNING;
                } else {
                    state = State.TERMINAL;
                    pendingOutcome = competingOutcome;
                    competingOutcome = null;
                }
                delivery = claimTerminalDelivery();
            }
            deliver(delivery);
            throw failure;
        }

        ConversationOutcome delivery;
        synchronized (lifecycleLock) {
            if (accepted) {
                state = State.TERMINAL;
                pendingOutcome = ConversationOutcome.cancelled(reason);
                competingOutcome = null;
            } else if (competingOutcome != null) {
                state = State.TERMINAL;
                pendingOutcome = competingOutcome;
                competingOutcome = null;
            } else {
                state = State.RUNNING;
            }
            delivery = claimTerminalDelivery();
        }
        deliver(delivery);
        return accepted;
    }

    private ConversationOutcome claimTerminalDelivery() {
        if (state == State.TERMINAL && pendingOutcome != null
                && inFlightEvents == 0 && !terminalDelivered) {
            terminalDelivered = true;
            return pendingOutcome;
        }
        return null;
    }

    private void deliver(ConversationOutcome outcome) {
        if (outcome != null) delegate.onTerminal(outcome);
    }

    public boolean isTerminal() {
        synchronized (lifecycleLock) {
            return state == State.TERMINAL;
        }
    }
}
