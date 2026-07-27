package com.javaclaw.api.conversation;

import java.util.Objects;
import java.util.function.Function;

/** 由模式适配器使用的标准运行句柄。 */
public final class DefaultConversationHandle implements ConversationHandle {

    private final TerminalCallbackGuard callbacks;
    private final Function<CancellationReason, Boolean> cancelAction;

    public DefaultConversationHandle(TerminalCallbackGuard callbacks,
                                     Function<CancellationReason, Boolean> cancelAction) {
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.cancelAction = Objects.requireNonNull(cancelAction, "cancelAction");
    }

    @Override
    public boolean cancel(CancellationReason reason) {
        return callbacks.cancel(reason, cancelAction);
    }

    @Override
    public boolean isTerminal() {
        return callbacks.isTerminal();
    }
}
