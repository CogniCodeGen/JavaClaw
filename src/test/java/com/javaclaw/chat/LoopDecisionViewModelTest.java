package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopDecisionViewModelTest {

    @Test
    void movesFromPromptToContinueResolution() {
        LoopDecisionViewModel model = new LoopDecisionViewModel();

        model.configure("read_file", 4);

        assertTrue(model.awaitingDecisionProperty().get());
        assertEquals("检测到工具 [read_file] 连续 4 次相似调用，已暂停。是否继续执行？",
                model.promptProperty().get());

        model.resolve(true);

        assertFalse(model.awaitingDecisionProperty().get());
        assertEquals("已选择继续执行", model.resolutionProperty().get());
    }

    @Test
    void recordsStopResolution() {
        LoopDecisionViewModel model = new LoopDecisionViewModel();
        model.configure("shell", 2);

        model.resolve(false);

        assertFalse(model.awaitingDecisionProperty().get());
        assertEquals("已选择终止", model.resolutionProperty().get());
    }
}
