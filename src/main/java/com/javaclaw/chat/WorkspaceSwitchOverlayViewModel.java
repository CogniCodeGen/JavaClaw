package com.javaclaw.chat;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 工作区切换遮罩的可见性与提示文案。 */
final class WorkspaceSwitchOverlayViewModel {

    private final BooleanProperty visible = new SimpleBooleanProperty(false);
    private final StringProperty message = new SimpleStringProperty("正在切换工作区...");

    void show(String text) {
        message.set(text == null || text.isBlank() ? "正在切换工作区..." : text);
        visible.set(true);
    }

    void hide() {
        visible.set(false);
    }

    BooleanProperty visibleProperty() { return visible; }
    StringProperty messageProperty() { return message; }
}
