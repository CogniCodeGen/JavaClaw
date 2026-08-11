package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingPanelViewModelTest {

    @Test
    void exposesDeterministicPageStateTransitions() {
        ThinkingPanelViewModel model = new ThinkingPanelViewModel();
        assertEquals("idle", model.statusTypeProperty().get());
        assertEquals("等待中", model.statusTextProperty().get());
        assertTrue(model.emptyProperty().get());

        model.setEmpty(false);
        model.setStatus("executing", "知识专家 思考中...");
        model.setElapsed("1.2s");
        model.setMetrics(120, 48, "¥0.03");

        assertFalse(model.emptyProperty().get());
        assertEquals("executing", model.statusTypeProperty().get());
        assertEquals("知识专家 思考中...", model.statusTextProperty().get());
        assertEquals("1.2s", model.elapsedProperty().get());
        assertEquals(120, model.tokensInProperty().get());
        assertEquals(48, model.tokensOutProperty().get());
        assertEquals("¥0.03", model.costProperty().get());
    }
}
