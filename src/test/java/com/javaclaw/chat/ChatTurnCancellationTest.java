package com.javaclaw.chat;

import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.DefaultConversationHandle;
import com.javaclaw.api.conversation.TerminalCallbackGuard;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTurnCancellationTest {

    @Test
    void 破坏性停止在句柄同步发送终态前先失效旧代次() {
        AtomicBoolean invalidated = new AtomicBoolean();
        AtomicBoolean terminalObservedAfterInvalidation = new AtomicBoolean();
        TerminalCallbackGuard callbacks = new TerminalCallbackGuard(new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
            }

            @Override
            public void onTerminal(ConversationOutcome outcome) {
                terminalObservedAfterInvalidation.set(invalidated.get());
            }
        });
        DefaultConversationHandle handle =
                new DefaultConversationHandle(callbacks, ignored -> true);

        ChatViewController.invalidateBeforeCancel(
                () -> invalidated.set(true), handle, CancellationReason.SESSION_SWITCH);

        assertTrue(invalidated.get());
        assertTrue(terminalObservedAfterInvalidation.get());
    }

    @Test
    void 已产生终态的句柄拒绝重复取消但仍可识别为终态() {
        TerminalCallbackGuard callbacks = new TerminalCallbackGuard(new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
            }

            @Override
            public void onTerminal(ConversationOutcome outcome) {
            }
        });
        DefaultConversationHandle handle =
                new DefaultConversationHandle(callbacks, ignored -> true);
        callbacks.onTerminal(ConversationOutcome.completed());

        assertFalse(handle.cancel(CancellationReason.USER_REQUEST));
        assertTrue(handle.isTerminal());
    }
}
