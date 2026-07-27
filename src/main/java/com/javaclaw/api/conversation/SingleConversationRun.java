package com.javaclaw.api.conversation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 单服务单运行门禁：拒绝重叠启动，并确保每个句柄只能取消创建它的那一代运行。
 */
public final class SingleConversationRun {

    private final AtomicReference<RunSlot> active = new AtomicReference<>();

    public ConversationHandle start(
            ConversationCallbacks callbacks,
            Consumer<ConversationCallbacks> starter,
            Function<CancellationReason, Boolean> cancelAction) {
        Objects.requireNonNull(callbacks, "callbacks");
        Objects.requireNonNull(starter, "starter");
        Objects.requireNonNull(cancelAction, "cancelAction");

        RunSlot slot = new RunSlot(callbacks, cancelAction);
        if (!active.compareAndSet(null, slot)) {
            TerminalCallbackGuard rejected = new TerminalCallbackGuard(callbacks);
            rejected.onTerminal(ConversationOutcome.failed(
                    new IllegalStateException("已有运行正在执行，请先等待完成或取消当前运行")));
            return new DefaultConversationHandle(rejected, ignored -> false);
        }

        try {
            starter.accept(slot.callbacks);
        } catch (Throwable failure) {
            slot.callbacks.onTerminal(ConversationOutcome.failed(failure));
        }
        return slot.handle;
    }

    public boolean cancelActive(CancellationReason reason) {
        RunSlot slot = active.get();
        return slot != null && slot.handle.cancel(reason);
    }

    public boolean isRunning() {
        return active.get() != null;
    }

    private final class RunSlot {
        private final TerminalCallbackGuard callbacks;
        private final ConversationHandle handle;

        private RunSlot(ConversationCallbacks delegate,
                        Function<CancellationReason, Boolean> cancelAction) {
            this.callbacks = new TerminalCallbackGuard(new ConversationCallbacks() {
                @Override
                public void onEvent(ConversationEvent event) {
                    delegate.onEvent(event);
                }

                @Override
                public void onTerminal(ConversationOutcome outcome) {
                    active.compareAndSet(RunSlot.this, null);
                    delegate.onTerminal(outcome);
                }
            });
            this.handle = new DefaultConversationHandle(callbacks, reason ->
                    active.get() == RunSlot.this && cancelAction.apply(reason));
        }
    }
}
