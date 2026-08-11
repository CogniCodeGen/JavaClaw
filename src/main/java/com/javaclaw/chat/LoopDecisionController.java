package com.javaclaw.chat;

import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** FXML Controller：将继续/终止点击精确提交一次。 */
public final class LoopDecisionController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LoopDecisionController.class);

    @FXML private HBox root;
    @FXML private Label promptLabel;
    @FXML private Button continueButton;
    @FXML private Button stopButton;
    @FXML private Label resolutionLabel;

    private final LoopDecisionViewModel viewModel = new LoopDecisionViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Consumer<Boolean> decision = ignored -> { };

    @FXML
    private void initialize() {
        promptLabel.textProperty().bind(viewModel.promptProperty());
        resolutionLabel.textProperty().bind(viewModel.resolutionProperty());
        promptLabel.visibleProperty().bind(viewModel.awaitingDecisionProperty());
        promptLabel.managedProperty().bind(promptLabel.visibleProperty());
        continueButton.visibleProperty().bind(viewModel.awaitingDecisionProperty());
        continueButton.managedProperty().bind(continueButton.visibleProperty());
        stopButton.visibleProperty().bind(viewModel.awaitingDecisionProperty());
        stopButton.managedProperty().bind(stopButton.visibleProperty());
        resolutionLabel.visibleProperty().bind(
                Bindings.not(viewModel.awaitingDecisionProperty()));
        resolutionLabel.managedProperty().bind(resolutionLabel.visibleProperty());
    }

    void configure(String toolName, int repeats, Consumer<Boolean> action) {
        decision = Objects.requireNonNull(action, "action");
        viewModel.configure(toolName, repeats);
    }

    void attach(LoopDecisionView view) {
        root.getProperties().put("loopDecisionView", view);
    }

    @FXML
    private void continueRequested() {
        resolve(true);
    }

    @FXML
    private void stopRequested() {
        resolve(false);
    }

    private void resolve(boolean continueRunning) {
        if (!viewModel.awaitingDecisionProperty().get()) return;
        viewModel.resolve(continueRunning);
        try {
            decision.accept(continueRunning);
        } catch (RuntimeException failure) {
            log.warn("循环检测决定回调异常", failure);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) decision = ignored -> { };
    }

    boolean isClosed() {
        return closed.get();
    }
}
