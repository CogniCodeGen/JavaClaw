package com.javaclaw.ui.javafx.task;

import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;

final class SddScenarioCell extends ListCell<SddScenarioRow> {
    @FXML private VBox root;
    @FXML private Label capabilityLabel;
    @FXML private Label verdictLabel;
    @FXML private Label givenLabel;
    @FXML private Label whenLabel;
    @FXML private Label thenLabel;

    SddScenarioCell() {
        VBox content = EmbeddedFxmlLoader.load(SddScenarioCell.class.getResource(
                "/fxml/task/sdd-scenario-cell.fxml"), this, VBox.class);
        if (content != root) throw new IllegalStateException("SddScenarioCell FXML 根节点不一致");
    }

    @Override protected void updateItem(SddScenarioRow row, boolean empty) {
        super.updateItem(row, empty);
        setText(null);
        if (empty || row == null) { setGraphic(null); return; }
        capabilityLabel.setText("能力 · " + text(row.capability()));
        verdictLabel.setText(row.passed() ? "✓ 通过" : "○ 待核验");
        verdictLabel.getStyleClass().setAll("jc-badge", "sdd-card-badge",
                row.passed() ? "jc-badge-running" : "jc-badge-stopped");
        givenLabel.setText(text(row.scenario().given()));
        whenLabel.setText(text(row.scenario().when()));
        thenLabel.setText(text(row.scenario().then()));
        setGraphic(root);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
