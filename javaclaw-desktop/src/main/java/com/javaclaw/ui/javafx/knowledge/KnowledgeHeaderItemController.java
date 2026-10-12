package com.javaclaw.ui.javafx.knowledge;

import javafx.fxml.FXML;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;

import java.util.concurrent.atomic.AtomicBoolean;

/** FXML Controller：绑定不可点击的知识库分组标题。 */
public final class KnowledgeHeaderItemController implements AutoCloseable {

    @FXML private CustomMenuItem root;
    @FXML private Label titleLabel;

    private final KnowledgeMenuEntryViewModel viewModel = new KnowledgeMenuEntryViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();

    @FXML
    private void initialize() {
        titleLabel.textProperty().bind(viewModel.textProperty());
    }

    void configure(String title) {
        viewModel.configure(title, false, false);
    }

    @Override
    public void close() {
        closed.set(true);
    }

    boolean isClosed() { return closed.get(); }
}
