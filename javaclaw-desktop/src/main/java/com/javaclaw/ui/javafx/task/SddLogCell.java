package com.javaclaw.ui.javafx.task;

import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

final class SddLogCell extends ListCell<SddLogEntry> {
    @FXML private HBox root;
    @FXML private Region dot;
    @FXML private Label timeLabel;
    @FXML private Label messageLabel;

    SddLogCell() {
        HBox content = EmbeddedFxmlLoader.load(SddLogCell.class.getResource(
                "/fxml/task/sdd-log-cell.fxml"), this, HBox.class);
        if (content != root) throw new IllegalStateException("SddLogCell FXML 根节点不一致");
    }

    @Override protected void updateItem(SddLogEntry entry, boolean empty) {
        super.updateItem(entry, empty);
        setText(null);
        if (empty || entry == null) { setGraphic(null); return; }
        dot.getStyleClass().setAll("sdd-log-dot", switch (entry.kind()) {
            case OK -> "sdd-log-dot-ok";
            case WARN -> "sdd-log-dot-warn";
            case INFO -> "sdd-log-dot-info";
            case DEFAULT -> "sdd-log-dot-hint";
        });
        timeLabel.setText(entry.time());
        messageLabel.setText(entry.message());
        setGraphic(root);
    }
}
