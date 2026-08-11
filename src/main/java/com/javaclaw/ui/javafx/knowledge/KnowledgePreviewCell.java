package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;

/** Virtualized document preview card. */
public final class KnowledgePreviewCell extends ListCell<String> {
    @FXML private VBox root;
    @FXML private Label indexLabel;
    @FXML private Label contentLabel;

    KnowledgePreviewCell() {
        VBox loaded = EmbeddedFxmlLoader.load(
                KnowledgePreviewCell.class.getResource(
                        "/fxml/knowledge/knowledge-preview-cell.fxml"),
                this, VBox.class);
        if (loaded != root) throw new IllegalStateException("知识预览 Cell FXML 根节点不一致");
    }

    @Override
    protected void updateItem(String content, boolean empty) {
        super.updateItem(content, empty);
        setText(null);
        if (empty || content == null) {
            setGraphic(null);
            return;
        }
        indexLabel.setText(String.format("#%02d", getIndex() + 1));
        contentLabel.setText(content);
        setGraphic(root);
    }
}
