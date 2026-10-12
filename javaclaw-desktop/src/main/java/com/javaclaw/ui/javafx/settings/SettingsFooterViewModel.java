package com.javaclaw.ui.javafx.settings;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 设置页脚的按钮能力与瞬态结果状态。 */
public final class SettingsFooterViewModel {

    private final BooleanProperty saveDisabled = new SimpleBooleanProperty(true);
    private final BooleanProperty testDisabled = new SimpleBooleanProperty(true);
    private final StringProperty testLabel = new SimpleStringProperty("测试连接");
    private final StringProperty statusText = new SimpleStringProperty("");
    private final StringProperty statusStyle = new SimpleStringProperty("");
    private final BooleanProperty warning = new SimpleBooleanProperty(false);

    public BooleanProperty saveDisabledProperty() { return saveDisabled; }
    public BooleanProperty testDisabledProperty() { return testDisabled; }
    public StringProperty testLabelProperty() { return testLabel; }
    public StringProperty statusTextProperty() { return statusText; }
    public StringProperty statusStyleProperty() { return statusStyle; }
    public BooleanProperty warningProperty() { return warning; }

    public void capabilities(boolean saveSupported, boolean dirty,
                             boolean testSupported, boolean testing, String label) {
        saveDisabled.set(!saveSupported || !dirty);
        testDisabled.set(!testSupported || testing);
        testLabel.set(testing ? "测试中…"
                : testSupported && label != null ? label : "测试连接");
    }

    public void status(String text, String style, boolean showWarning) {
        statusText.set(text == null ? "" : text);
        statusStyle.set(style == null ? "" : style);
        warning.set(showWarning);
    }
}
