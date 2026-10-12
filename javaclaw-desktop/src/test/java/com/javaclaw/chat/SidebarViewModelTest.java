package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SidebarViewModelTest {

    @Test
    void startsWithNeutralPageState() {
        SidebarViewModel model = new SidebarViewModel();

        assertEquals("", model.searchQueryProperty().get());
        assertEquals(0, model.sessionCountProperty().get());
        assertFalse(model.batchModeProperty().get());
        assertEquals(null, model.selectedSessionIdProperty().get());
    }
}
