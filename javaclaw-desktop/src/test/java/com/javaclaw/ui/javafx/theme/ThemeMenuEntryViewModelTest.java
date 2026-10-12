package com.javaclaw.ui.javafx.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThemeMenuEntryViewModelTest {

    @Test
    void mapsThemePaletteAndSelectionMark() {
        ThemeMenuEntryViewModel model = new ThemeMenuEntryViewModel();
        ThemeOption option = new ThemeOption(
                "ocean", "海洋 Ocean", "青绿强调",
                "#0E8C8C", "#F6FAFA", "#FFFFFF");

        model.configure(option, true);

        assertEquals("海洋 Ocean", model.nameProperty().get());
        assertEquals("青绿强调", model.subtitleProperty().get());
        assertEquals("#0E8C8C", model.brandProperty().get());
        assertEquals("#F6FAFA", model.backgroundProperty().get());
        assertEquals("#FFFFFF", model.surfaceProperty().get());
        assertEquals("✓", model.selectedMarkProperty().get());

        model.configure(option, false);
        assertEquals(" ", model.selectedMarkProperty().get());
    }
}
