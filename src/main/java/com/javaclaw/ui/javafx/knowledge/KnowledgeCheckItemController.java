package com.javaclaw.ui.javafx.knowledge;

import javafx.fxml.FXML;
import javafx.scene.control.CheckMenuItem;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** FXML Controller：管理一个知识库勾选项并转发其布尔状态。 */
public final class KnowledgeCheckItemController implements AutoCloseable {

    @FXML private CheckMenuItem root;

    private final KnowledgeMenuEntryViewModel viewModel = new KnowledgeMenuEntryViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Consumer<Boolean> selection = ignored -> { };

    @FXML
    private void initialize() {
        root.textProperty().bind(viewModel.textProperty());
        root.selectedProperty().bindBidirectional(viewModel.selectedProperty());
    }

    void configure(String text, boolean selected, Consumer<Boolean> selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
        viewModel.configure(text, selected, false);
    }

    @FXML
    private void selectionChanged() {
        if (!closed.get()) selection.accept(root.isSelected());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) selection = ignored -> { };
    }

    boolean isClosed() { return closed.get(); }
}
