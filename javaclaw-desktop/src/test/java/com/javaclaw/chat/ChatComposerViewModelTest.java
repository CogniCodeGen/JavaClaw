package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatComposerViewModelTest {

    @Test
    void keepsStreamingThinkingAndAttachmentStateWithoutServices() {
        ChatComposerViewModel model = new ChatComposerViewModel();
        File first = new File("first.md");
        File second = new File("second.png");

        model.streamingProperty().set(true);
        model.blockedProperty().set(true);
        model.thinkingProperty().set(true);
        model.thinkingTextProperty().set("正在路由...");
        model.addAttachments(List.of(first, first, second));

        assertTrue(model.streamingProperty().get());
        assertTrue(model.blockedProperty().get());
        assertTrue(model.thinkingProperty().get());
        assertEquals("正在路由...", model.thinkingTextProperty().get());
        assertEquals(List.of(first, second), model.attachmentSnapshot());

        model.attachments().clear();
        model.blockedProperty().set(false);
        assertTrue(model.streamingProperty().get());
        assertFalse(model.blockedProperty().get());
        assertTrue(model.attachmentSnapshot().isEmpty());
    }
}
