package com.javaclaw.ui.javafx.loop;

import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.LoopStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopStatusViewModelTest {

    @Test
    void mapsRunningSnapshotIncludingCriteriaAndDelay() {
        LoopStatusViewModel model = new LoopStatusViewModel();

        model.update(new LoopStatus(3, Decision.CONTINUE, "等待外部审批", 2, 5, 240, 8));

        assertEquals("第 3 轮", model.iterationProperty().get());
        assertEquals("进行中", model.badgeTextProperty().get());
        assertEquals("loop-badge-run", model.badgeStyleClassProperty().get());
        assertTrue(model.criteriaVisibleProperty().get());
        assertEquals(0.4, model.criteriaProgressProperty().get());
        assertEquals("已满足 2/5", model.criteriaTextProperty().get());
        assertTrue(model.reasonVisibleProperty().get());
        assertEquals("等待外部审批", model.reasonProperty().get());
        assertEquals("累计用量 240 tokens · ⏳ 8s 后开下一轮",
                model.tokensProperty().get());
    }

    @Test
    void cancellationOverridesTransientStateAndKeepsUsage() {
        LoopStatusViewModel model = new LoopStatusViewModel();
        model.update(new LoopStatus(2, Decision.CONTINUE, "等待", 0, 0, 91, 5));

        model.markCancelled();

        assertEquals("已停止", model.badgeTextProperty().get());
        assertEquals("loop-badge-stop", model.badgeStyleClassProperty().get());
        assertEquals("用户取消", model.reasonProperty().get());
        assertTrue(model.reasonVisibleProperty().get());
        assertEquals("累计用量 91 tokens", model.tokensProperty().get());
    }

    @Test
    void completedSnapshotCanHideEmptySections() {
        LoopStatusViewModel model = new LoopStatusViewModel();

        model.update(new LoopStatus(4, Decision.DONE, null, 0, 0, 320, 0));

        assertEquals("已完成", model.badgeTextProperty().get());
        assertEquals("loop-badge-done", model.badgeStyleClassProperty().get());
        assertFalse(model.criteriaVisibleProperty().get());
        assertFalse(model.reasonVisibleProperty().get());
        assertEquals("累计用量 320 tokens", model.tokensProperty().get());
    }
}
