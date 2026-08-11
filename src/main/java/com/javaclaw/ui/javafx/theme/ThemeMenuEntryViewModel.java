package com.javaclaw.ui.javafx.theme;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 单个主题菜单项的文本、色板和选中状态。 */
final class ThemeMenuEntryViewModel {

    private final StringProperty name = new SimpleStringProperty("");
    private final StringProperty subtitle = new SimpleStringProperty("");
    private final StringProperty brand = new SimpleStringProperty("transparent");
    private final StringProperty background = new SimpleStringProperty("transparent");
    private final StringProperty surface = new SimpleStringProperty("transparent");
    private final StringProperty selectedMark = new SimpleStringProperty(" ");

    void configure(ThemeOption option, boolean selected) {
        name.set(option.name());
        subtitle.set(option.subtitle());
        brand.set(option.brand());
        background.set(option.background());
        surface.set(option.surface());
        selectedMark.set(selected ? "✓" : " ");
    }

    StringProperty nameProperty() { return name; }
    StringProperty subtitleProperty() { return subtitle; }
    StringProperty brandProperty() { return brand; }
    StringProperty backgroundProperty() { return background; }
    StringProperty surfaceProperty() { return surface; }
    StringProperty selectedMarkProperty() { return selectedMark; }
}
