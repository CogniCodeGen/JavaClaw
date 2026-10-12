package com.javaclaw.ui.javafx.knowledge;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 动态知识库菜单项共享的文本、选中和禁用状态。 */
final class KnowledgeMenuEntryViewModel {

    private final StringProperty text = new SimpleStringProperty("");
    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final BooleanProperty disabled = new SimpleBooleanProperty(false);

    void configure(String value, boolean selectedValue, boolean disabledValue) {
        text.set(value == null ? "" : value);
        selected.set(selectedValue);
        disabled.set(disabledValue);
    }

    StringProperty textProperty() { return text; }
    BooleanProperty selectedProperty() { return selected; }
    BooleanProperty disabledProperty() { return disabled; }
}
