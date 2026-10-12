package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClarificationCardViewModelTest {

    @Test
    void exposesHeaderAndOnlyNonBlankSections() {
        ClarificationCardViewModel model = new ClarificationCardViewModel();

        model.configure("JavaClaw", "gpt-5", "10:30", "  ", "选择哪个工作区？");

        assertEquals("JavaClaw", model.agentNameProperty().get());
        assertEquals("gpt-5", model.modelNameProperty().get());
        assertEquals("10:30", model.timestampProperty().get());
        assertFalse(model.reasonVisibleProperty().get());
        assertTrue(model.questionVisibleProperty().get());
    }

    @Test
    void hidesBothOptionalSectionsWhenContentIsMissing() {
        ClarificationCardViewModel model = new ClarificationCardViewModel();

        model.configure("JavaClaw", "gpt-5", "10:30", null, "");

        assertFalse(model.reasonVisibleProperty().get());
        assertFalse(model.questionVisibleProperty().get());
    }
}
