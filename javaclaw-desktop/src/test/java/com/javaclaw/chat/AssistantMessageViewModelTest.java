package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantMessageViewModelTest {

    @Test
    void exposesOnlyPresentationStateTransitions() {
        AssistantMessageViewModel model = new AssistantMessageViewModel();

        model.configure("JavaClaw", "gpt-5", "10:30");
        model.showTools();
        model.revealReply();
        model.enableAdoption();
        model.hideReplyCard();

        assertEquals("JavaClaw", model.agentNameProperty().get());
        assertEquals("gpt-5", model.modelNameProperty().get());
        assertEquals("10:30", model.timestampProperty().get());
        assertTrue(model.toolsVisibleProperty().get());
        assertTrue(model.replyVisibleProperty().get());
        assertTrue(model.adoptionEnabledProperty().get());
        assertFalse(model.replyCardVisibleProperty().get());
    }
}
