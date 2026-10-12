package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSwitchOverlayViewModelTest {

    @Test
    void showAndHideOwnObservableOverlayState() {
        WorkspaceSwitchOverlayViewModel model = new WorkspaceSwitchOverlayViewModel();
        assertFalse(model.visibleProperty().get());

        model.show("正在加载新工作区");

        assertTrue(model.visibleProperty().get());
        assertEquals("正在加载新工作区", model.messageProperty().get());

        model.hide();
        assertFalse(model.visibleProperty().get());
    }

    @Test
    void blankMessageUsesStableDefault() {
        WorkspaceSwitchOverlayViewModel model = new WorkspaceSwitchOverlayViewModel();

        model.show("  ");

        assertEquals("正在切换工作区...", model.messageProperty().get());
    }
}
