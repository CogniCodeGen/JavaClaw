package com.javaclaw.ui.javafx.control;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** Window Toast 的纯页面状态，不持有动画、窗口或服务。 */
public final class WindowToastViewModel {

    private final StringProperty message = new SimpleStringProperty("");
    private final BooleanProperty visible = new SimpleBooleanProperty(false);

    public StringProperty messageProperty() {
        return message;
    }

    public BooleanProperty visibleProperty() {
        return visible;
    }
}
