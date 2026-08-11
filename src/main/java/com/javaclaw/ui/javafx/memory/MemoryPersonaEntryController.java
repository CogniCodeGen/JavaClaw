package com.javaclaw.ui.javafx.memory;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.util.Objects;
import java.util.function.Consumer;

/** 人格偏好或禁忌条目。 */
public final class MemoryPersonaEntryController {
    @FXML private HBox root;
    @FXML private Label icon;
    @FXML private Label text;
    private String value = "";
    private Consumer<String> remove = ignored -> {};

    void configure(String value, boolean danger, Consumer<String> remove) {
        this.value = value;
        this.remove = Objects.requireNonNull(remove, "remove");
        icon.setText(danger ? "⊘" : "✓");
        icon.setStyle("-fx-font-size: 12px; -fx-text-fill: "
                + (danger ? "-jc-danger" : "-jc-success") + ";");
        text.setText(value);
        root.setStyle("-fx-background-color: "
                + (danger ? "-jc-danger-bg" : "-jc-success-bg")
                + "; -fx-background-radius: 8; -fx-padding: 8 11 8 11;");
    }

    @FXML private void removeRequested() { remove.accept(value); }
}
