package com.javaclaw.ui.javafx.task;

import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

final class SddChangeCell extends ListCell<String> {
    @FXML private HBox root;
    @FXML private Label numberLabel;
    @FXML private Label textLabel;

    SddChangeCell() {
        HBox content = EmbeddedFxmlLoader.load(SddChangeCell.class.getResource(
                "/fxml/task/sdd-change-cell.fxml"), this, HBox.class);
        if (content != root) throw new IllegalStateException("SddChangeCell FXML 根节点不一致");
    }

    @Override protected void updateItem(String value, boolean empty) {
        super.updateItem(value, empty);
        setText(null);
        if (empty || value == null) { setGraphic(null); return; }
        numberLabel.setText(String.format("%02d", getIndex() + 1));
        textLabel.setText(value);
        setGraphic(root);
    }
}
