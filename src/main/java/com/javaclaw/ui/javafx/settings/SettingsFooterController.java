package com.javaclaw.ui.javafx.settings;

import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.Objects;

/** 设置窗口页脚 Controller；统一呈现保存、测试和脏状态。 */
public final class SettingsFooterController implements AutoCloseable {

    private static final String[] STATUS_CLASSES = {
            "status-success", "status-error", "status-info", "status-warn"
    };

    @FXML private Button testButton;
    @FXML private Button saveButton;
    @FXML private Label statusLabel;
    @FXML private Region warningDot;
    @FXML private Tooltip testTooltip;
    @FXML private Tooltip saveTooltip;

    private final SettingsFooterViewModel viewModel = new SettingsFooterViewModel();
    private final PauseTransition savedTipTimer = new PauseTransition(Duration.millis(2200));
    private Runnable onSave = () -> { };
    private Runnable onTest = () -> { };
    private Runnable onClose = () -> { };

    @FXML
    private void initialize() {
        saveButton.disableProperty().bind(viewModel.saveDisabledProperty());
        testButton.disableProperty().bind(viewModel.testDisabledProperty());
        testButton.textProperty().bind(viewModel.testLabelProperty());
        statusLabel.textProperty().bind(viewModel.statusTextProperty());
        warningDot.visibleProperty().bind(viewModel.warningProperty());
        warningDot.managedProperty().bind(viewModel.warningProperty());
        viewModel.statusStyleProperty().addListener((ignored, previous, value) -> renderStyle(value));
        savedTipTimer.setOnFinished(event -> clearStatus());
    }

    public SettingsFooterViewModel viewModel() {
        return viewModel;
    }

    public void configure(Runnable save, Runnable test, Runnable close) {
        onSave = Objects.requireNonNull(save, "save");
        onTest = Objects.requireNonNull(test, "test");
        onClose = Objects.requireNonNull(close, "close");
    }

    public void capabilities(boolean saveSupported, boolean dirty,
                             boolean testSupported, boolean testing, String testLabel) {
        viewModel.capabilities(saveSupported, dirty, testSupported, testing, testLabel);
        saveTooltip.setText(saveSupported
                ? "⌘S / Ctrl+S" : "当前分区的更改在分区内即时生效或保存");
        testTooltip.setText(testSupported ? "执行当前分区的连接测试" : "当前分区无连接测试");
    }

    public void clearStatus() {
        savedTipTimer.stop();
        viewModel.status("", "", false);
    }

    public void showUnsaved() {
        savedTipTimer.stop();
        viewModel.status("有未保存的更改", "status-warn", true);
    }

    public void showSaved(String text) {
        viewModel.status(text, "status-success", false);
        savedTipTimer.playFromStart();
    }

    public void showInfo(String text) {
        savedTipTimer.stop();
        viewModel.status(text, "status-info", false);
    }

    public void showResult(String text, boolean succeeded) {
        savedTipTimer.stop();
        viewModel.status(text, succeeded ? "status-success" : "status-error", false);
    }

    @FXML private void saveRequested() { onSave.run(); }
    @FXML private void testRequested() { onTest.run(); }
    @FXML private void closeRequested() { onClose.run(); }

    private void renderStyle(String style) {
        statusLabel.getStyleClass().removeAll(STATUS_CLASSES);
        if (style != null && !style.isBlank()) statusLabel.getStyleClass().add(style);
    }

    @Override
    public void close() {
        savedTipTimer.stop();
        savedTipTimer.setOnFinished(null);
        saveButton.disableProperty().unbind();
        testButton.disableProperty().unbind();
        testButton.textProperty().unbind();
        statusLabel.textProperty().unbind();
        warningDot.visibleProperty().unbind();
        warningDot.managedProperty().unbind();
        onSave = onTest = onClose = () -> { };
    }
}
