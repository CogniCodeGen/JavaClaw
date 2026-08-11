package com.javaclaw.ui.javafx.knowledge;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 知识库菜单按钮的文案和激活状态。 */
final class KnowledgeMenuViewModel {

    private final StringProperty buttonText = new SimpleStringProperty("📖 知识库");
    private final BooleanProperty active = new SimpleBooleanProperty(false);

    void updateSelectedCount(int selectedCount) {
        boolean hasSelection = selectedCount > 0;
        buttonText.set(hasSelection ? "知识库(" + selectedCount + ")" : "知识库");
        active.set(hasSelection);
    }

    StringProperty buttonTextProperty() { return buttonText; }
    BooleanProperty activeProperty() { return active; }
}
