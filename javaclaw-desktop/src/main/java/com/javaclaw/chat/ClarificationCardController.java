package com.javaclaw.chat;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.InlineCssTextArea;

import java.util.concurrent.atomic.AtomicBoolean;

/** FXML Controller：映射主动澄清的可选原因和必需问题区域。 */
public final class ClarificationCardController implements AutoCloseable {

    @FXML private HBox root;
    @FXML private Label agentNameLabel;
    @FXML private Label modelBadge;
    @FXML private Label timeLabel;
    @FXML private VBox reasonSection;
    @FXML private InlineCssTextArea reasonText;
    @FXML private VBox questionSection;
    @FXML private InlineCssTextArea questionText;

    private final ClarificationCardViewModel viewModel = new ClarificationCardViewModel();
    private final AtomicBoolean closed = new AtomicBoolean();

    @FXML
    private void initialize() {
        agentNameLabel.textProperty().bind(viewModel.agentNameProperty());
        modelBadge.textProperty().bind(viewModel.modelNameProperty());
        timeLabel.textProperty().bind(viewModel.timestampProperty());
        bindVisibility(reasonSection, viewModel.reasonVisibleProperty());
        bindVisibility(questionSection, viewModel.questionVisibleProperty());
    }

    void configure(
            String agentName, String modelName, String timestamp,
            String reason, String question) {
        viewModel.configure(agentName, modelName, timestamp, reason, question);
        BubbleTextAreaSupport.configure(reasonText, reason, 520, "-fx-fill: #4A3E20;");
        BubbleTextAreaSupport.configure(questionText, question, 520, "-fx-fill: #27251F;");
    }

    void attach(ClarificationCardView view) {
        root.getProperties().put("clarificationCardView", view);
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

    boolean isClosed() { return closed.get(); }
}
