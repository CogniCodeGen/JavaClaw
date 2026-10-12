package com.javaclaw.ui.javafx.knowledge;

import javafx.fxml.FXML;
import javafx.scene.control.MenuItem;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** FXML Controller：管理提示或导航菜单项并转发动作。 */
public final class KnowledgeActionItemController implements AutoCloseable {

    @FXML private MenuItem root;

    private final KnowledgeMenuEntryViewModel viewModel = new KnowledgeMenuEntryViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Runnable action = () -> { };

    @FXML
    private void initialize() {
        root.textProperty().bind(viewModel.textProperty());
        root.disableProperty().bind(viewModel.disabledProperty());
    }

    void configure(String text, boolean disabled, String styleClass, Runnable action) {
        this.action = Objects.requireNonNull(action, "action");
        viewModel.configure(text, false, disabled);
        if (styleClass != null && !styleClass.isBlank()
                && !root.getStyleClass().contains(styleClass)) {
            root.getStyleClass().add(styleClass);
        }
    }

    @FXML
    private void requested() {
        if (!closed.get() && !root.isDisable()) action.run();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) action = () -> { };
    }

    boolean isClosed() { return closed.get(); }
}
