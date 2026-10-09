package com.javaclaw.ui.javafx.settings;

import com.javaclaw.desktop.api.DesktopInputPolicy;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns the host-only policy selector and its bindings. */
final class DesktopInputPolicyChoice implements AutoCloseable {
    private final ComboBox<DesktopInputPolicy> combo;
    private final ObjectProperty<DesktopInputPolicy> value;
    private final ChangeListener<DesktopInputPolicy> listener;

    DesktopInputPolicyChoice(ComboBox<DesktopInputPolicy> combo,
            ObjectProperty<DesktopInputPolicy> value, ObservableBooleanValue busy,
            Consumer<DesktopInputPolicy> changed) {
        this.combo = combo;
        this.value = value;
        combo.getItems().setAll(DesktopInputPolicy.values());
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(DesktopInputPolicy policy) {
                return policy == DesktopInputPolicy.SYSTEM_EXPLICIT
                        ? "系统输入（会占用鼠标键盘）" : "后台公开控件操作（默认）";
            }
            @Override public DesktopInputPolicy fromString(String text) { return null; }
        });
        listener = (ignored, before, policy) -> changed.accept(
                Objects.requireNonNullElse(policy, DesktopInputPolicy.BACKGROUND_STRICT));
        combo.valueProperty().addListener(listener);
        combo.valueProperty().bindBidirectional(value);
        combo.disableProperty().bind(busy);
    }

    @Override public void close() {
        combo.valueProperty().removeListener(listener);
        combo.valueProperty().unbindBidirectional(value);
        combo.disableProperty().unbind();
    }
}
