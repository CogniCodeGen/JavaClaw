package com.javaclaw.ui.javafx.memory;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** 图谱检视器中的一条关联记忆。 */
public final class MemoryRelatedItemController {
    @FXML private Label text;
    void configure(String value) { text.setText(value == null ? "" : value); }
}
