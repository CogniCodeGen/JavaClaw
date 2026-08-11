package com.javaclaw.ui.javafx.settings;

import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 可释放的设置表单变更观察器。 */
final class SettingsDirtyTracker implements AutoCloseable {

    private final List<Runnable> removers = new ArrayList<>();

    void watch(Node root, Runnable changed) {
        Objects.requireNonNull(changed, "changed");
        visit(root, root, changed);
    }

    private void visit(Node node, Node root, Runnable changed) {
        if (node == null || node.getProperties().containsKey("jc-dirty-exempt")) return;
        switch (node) {
            case TextInputControl control -> listen(control.textProperty(), root, changed);
            case CheckBox control -> listen(control.selectedProperty(), root, changed);
            case RadioButton control -> listen(control.selectedProperty(), root, changed);
            case ToggleButton control when control.getStyleClass().contains("seg-btn") ->
                    listen(control.selectedProperty(), root, changed);
            case ToggleSwitch control -> listen(control.selectedProperty(), root, changed);
            case ComboBox<?> control -> listen(control.valueProperty(), root, changed);
            case ScrollPane scrollPane -> visit(scrollPane.getContent(), root, changed);
            case Parent parent -> parent.getChildrenUnmodifiable()
                    .forEach(child -> visit(child, root, changed));
            default -> { }
        }
    }

    private <T> void listen(ObservableValue<T> property, Node root, Runnable changed) {
        ChangeListener<T> listener = (ignored, previous, value) -> {
            if (!SettingsFieldSupport.isLoading(root)) changed.run();
        };
        property.addListener(listener);
        removers.add(() -> property.removeListener(listener));
    }

    @Override
    public void close() {
        for (int i = removers.size() - 1; i >= 0; i--) removers.get(i).run();
        removers.clear();
    }
}
