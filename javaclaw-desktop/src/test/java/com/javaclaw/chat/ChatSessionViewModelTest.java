package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionViewModelTest {

    @Test
    void tracksTailAndUnreadStateWithoutServicesOrNodes() {
        ChatSessionViewModel model = new ChatSessionViewModel();
        assertTrue(model.followTailProperty().get());
        assertEquals(0, model.unreadMessagesProperty().get());

        model.followTailProperty().set(false);
        model.incrementUnread(2);
        model.incrementUnread(0);
        assertFalse(model.followTailProperty().get());
        assertEquals(2, model.unreadMessagesProperty().get());

        model.resetUnread();
        assertEquals(0, model.unreadMessagesProperty().get());
    }
}
