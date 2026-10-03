package com.javaclaw.ui.javafx.settings;

import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;

/** Makes a row clickable without toggling twice when its switch was clicked. */
final class SettingsToggleRow {

    private SettingsToggleRow() { }

    static void toggle(MouseEvent event, ToggleSwitch toggle) {
        if (toggle.isDisabled() || originatesFrom(event, toggle)) return;
        toggle.setSelected(!toggle.isSelected());
    }

    private static boolean originatesFrom(MouseEvent event, Node expected) {
        Node current = event.getPickResult().getIntersectedNode();
        while (current != null) {
            if (current == expected) return true;
            current = current.getParent();
        }
        return false;
    }
}
