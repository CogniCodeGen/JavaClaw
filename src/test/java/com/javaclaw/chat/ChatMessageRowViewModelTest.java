package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageRowViewModelTest {

    @Test
    void assistantUsesNormalMarkdownPresentation() {
        ChatMessageRowViewModel model = new ChatMessageRowViewModel();
        model.configure(ChatMessageRowFactory.Variant.ASSISTANT,
                "JavaClaw", "gpt-5", "10:30", "1.2s", true, false);

        assertTrue(model.normalProperty().get());
        assertTrue(model.assistantProperty().get());
        assertTrue(model.markdownProperty().get());
        assertFalse(model.userProperty().get());
        assertFalse(model.plainTextProperty().get());
    }

    @Test
    void emptyUserMessageCanStillShowAttachments() {
        ChatMessageRowViewModel model = new ChatMessageRowViewModel();
        model.configure(ChatMessageRowFactory.Variant.USER,
                "You", "", "10:30", "—", false, true);

        assertTrue(model.normalProperty().get());
        assertTrue(model.userProperty().get());
        assertTrue(model.attachmentsProperty().get());
        assertFalse(model.plainTextProperty().get());
    }

    @Test
    void systemAndWelcomeUseDedicatedFxmlBranches() {
        ChatMessageRowViewModel model = new ChatMessageRowViewModel();
        model.configure(ChatMessageRowFactory.Variant.SYSTEM,
                "", "", "10:30", "—", true, false);
        assertTrue(model.systemProperty().get());
        assertFalse(model.normalProperty().get());

        model.configure(ChatMessageRowFactory.Variant.WELCOME,
                "", "", "10:30", "—", true, false);
        assertTrue(model.welcomeProperty().get());
        assertFalse(model.systemProperty().get());
    }
}
