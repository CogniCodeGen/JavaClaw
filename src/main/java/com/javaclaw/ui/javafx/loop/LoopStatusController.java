package com.javaclaw.ui.javafx.loop;

import com.javaclaw.loop.model.LoopStatus;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;

import java.util.concurrent.atomic.AtomicBoolean;

/** FXML Controller：绑定循环快照状态，不执行业务或后台任务。 */
public final class LoopStatusController implements AutoCloseable {

    private static final String[] BADGE_STYLES = {
            "loop-badge-run", "loop-badge-done", "loop-badge-stop"
    };

    @FXML private HBox root;
    @FXML private Label iterationLabel;
    @FXML private Label badge;
    @FXML private HBox criteriaRow;
    @FXML private ProgressBar criteriaBar;
    @FXML private Label criteriaLabel;
    @FXML private Label reasonLabel;
    @FXML private Label tokensLabel;

    private final LoopStatusViewModel viewModel = new LoopStatusViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();

    @FXML
    private void initialize() {
        iterationLabel.textProperty().bind(viewModel.iterationProperty());
        badge.textProperty().bind(viewModel.badgeTextProperty());
        criteriaBar.progressProperty().bind(viewModel.criteriaProgressProperty());
        criteriaLabel.textProperty().bind(viewModel.criteriaTextProperty());
        bindVisibility(criteriaRow, viewModel.criteriaVisibleProperty());
        reasonLabel.textProperty().bind(viewModel.reasonProperty());
        bindVisibility(reasonLabel, viewModel.reasonVisibleProperty());
        tokensLabel.textProperty().bind(viewModel.tokensProperty());
        viewModel.badgeStyleClassProperty().addListener(
                (ignored, oldStyle, newStyle) -> applyBadgeStyle(newStyle));
        applyBadgeStyle(viewModel.badgeStyleClassProperty().get());
    }

    void update(LoopStatus status) {
        viewModel.update(status);
    }

    void markCancelled() {
        viewModel.markCancelled();
    }

    void attach(LoopStatusView view) {
        root.getProperties().put("loopStatusView", view);
    }

    private void applyBadgeStyle(String styleClass) {
        badge.getStyleClass().removeAll(BADGE_STYLES);
        if (styleClass != null && !styleClass.isBlank()) {
            badge.getStyleClass().add(styleClass);
        }
    }

    private static void bindVisibility(
            javafx.scene.Node node, javafx.beans.value.ObservableBooleanValue visible) {
        node.visibleProperty().bind(visible);
        node.managedProperty().bind(visible);
    }

    @Override
    public void close() {
        closed.set(true);
    }

    boolean isClosed() {
        return closed.get();
    }
}
