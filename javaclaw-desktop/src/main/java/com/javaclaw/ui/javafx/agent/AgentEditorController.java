package com.javaclaw.ui.javafx.agent;

import com.javaclaw.application.agent.AgentManagementApplicationService.Agent;
import com.javaclaw.application.agent.AgentManagementApplicationService.OptimizePromptCommand;
import com.javaclaw.application.agent.AgentManagementApplicationService.SaveAgentCommand;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.Objects;

/** 智能体编辑器 FXML Controller：只负责表单展示、采集和事件转发。 */
public final class AgentEditorController implements AutoCloseable {

    @FXML private VBox root;
    @FXML private Label headerAvatar;
    @FXML private Label headerNameLabel;
    @FXML private Label headerSubLabel;
    @FXML private Label headerBadge;
    @FXML private CheckBox enabledCheck;
    @FXML private TextField nameField;
    @FXML private TextField toolNameField;
    @FXML private TextArea descriptionArea;
    @FXML private TextArea systemPromptArea;
    @FXML private VBox capabilityFormsBox;
    @FXML private Spinner<Integer> maxItersSpinner;
    @FXML private Button optimizePromptButton;
    @FXML private Button saveButton;
    @FXML private Button deleteButton;
    @FXML private Label statusLabel;

    private Runnable onSave = () -> {};
    private Runnable onOptimize = () -> {};
    private Runnable onDelete = () -> {};
    private Agent current;
    private AgentSettingsViewModel viewModel;
    private final CapabilityFormRenderer capabilityForms;

    public AgentEditorController(com.fasterxml.jackson.databind.ObjectMapper json) {
        this.capabilityForms = new CapabilityFormRenderer(json);
    }

    @FXML
    private void initialize() {
        maxItersSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 30, 1));
    }

    void configure(Runnable save, Runnable optimize, Runnable delete) {
        onSave = Objects.requireNonNull(save, "save");
        onOptimize = Objects.requireNonNull(optimize, "optimize");
        onDelete = Objects.requireNonNull(delete, "delete");
    }

    void bind(AgentSettingsViewModel model) {
        viewModel = Objects.requireNonNull(model, "model");
        statusLabel.textProperty().bind(model.statusProperty());
        statusLabel.visibleProperty().bind(model.statusProperty().isNotEmpty());
        statusLabel.managedProperty().bind(statusLabel.visibleProperty());
        model.statusErrorProperty().addListener((ignored, oldValue, error) -> applyStatusStyle());
        optimizePromptButton.disableProperty().bind(model.optimizingProperty());
        saveButton.disableProperty().bind(model.mutatingProperty());
        deleteButton.disableProperty().bind(model.mutatingProperty());
        applyStatusStyle();
    }

    void show(Agent agent, java.util.List<com.javaclaw.framework.api.CapabilityForm> forms) {
        current = Objects.requireNonNull(agent, "agent");
        boolean readOnly = agent.builtIn();
        root.setVisible(true);
        root.setManaged(true);
        headerAvatar.setText(readOnly ? "🛠" : "🤖");
        headerNameLabel.setText(agent.name().isBlank() ? "未命名智能体" : agent.name());
        headerSubLabel.setText("工具名 " + agent.toolName());
        headerBadge.setText(readOnly ? "内置" : "自定义");
        headerBadge.getStyleClass().removeAll(
                "agent-header-badge-builtin", "agent-header-badge-custom");
        headerBadge.getStyleClass().add(readOnly
                ? "agent-header-badge-builtin" : "agent-header-badge-custom");
        nameField.setText(agent.name());
        toolNameField.setText(agent.toolName());
        descriptionArea.setText(agent.description());
        systemPromptArea.setText(agent.systemPrompt());
        systemPromptArea.setPromptText(readOnly
                ? "内置智能体的系统提示词由系统维护，不可编辑"
                : "# 角色\n你是一名…\n\n# 能力\n…\n\n# 行为准则\n…");
        maxItersSpinner.getValueFactory().setValue(agent.maxIters());
        enabledCheck.setSelected(agent.enabled());
        capabilityForms.render(capabilityFormsBox, forms, agent.capabilityBindings(), readOnly);
        setReadOnly(readOnly);
    }

    void hide() {
        current = null;
        capabilityForms.clear(capabilityFormsBox);
        root.setVisible(false);
        root.setManaged(false);
    }

    SaveAgentCommand saveCommand() {
        if (current == null) throw new IllegalStateException("尚未选择智能体");
        return new SaveAgentCommand(current.id(), nameField.getText(), toolNameField.getText(),
                descriptionArea.getText(), systemPromptArea.getText(),
                maxItersSpinner.getValue(), enabledCheck.isSelected(), capabilityForms.values());
    }

    OptimizePromptCommand optimizeCommand() {
        return new OptimizePromptCommand(
                nameField.getText(), descriptionArea.getText(), systemPromptArea.getText());
    }

    Agent current() { return current; }

    void setGeneratedPrompt(String prompt) { systemPromptArea.setText(prompt); }

    void focusName() {
        nameField.requestFocus();
        nameField.selectAll();
    }

    @FXML private void saveRequested() { onSave.run(); }
    @FXML private void optimizeRequested() { onOptimize.run(); }
    @FXML private void deleteRequested() { onDelete.run(); }

    private void setReadOnly(boolean readOnly) {
        nameField.setDisable(readOnly);
        toolNameField.setDisable(readOnly);
        descriptionArea.setDisable(readOnly);
        systemPromptArea.setDisable(readOnly);
        maxItersSpinner.setDisable(readOnly);
        enabledCheck.setDisable(readOnly);
        optimizePromptButton.setVisible(!readOnly);
        optimizePromptButton.setManaged(!readOnly);
        saveButton.setVisible(!readOnly);
        saveButton.setManaged(!readOnly);
        deleteButton.setVisible(!readOnly);
        deleteButton.setManaged(!readOnly);
    }

    private void applyStatusStyle() {
        statusLabel.getStyleClass().removeAll("status-success", "status-error");
        if (viewModel != null && !viewModel.statusProperty().get().isBlank()) {
            statusLabel.getStyleClass().add(
                    viewModel.statusErrorProperty().get() ? "status-error" : "status-success");
        }
    }

    @Override
    public void close() {
        if (viewModel == null) return;
        statusLabel.textProperty().unbind();
        statusLabel.visibleProperty().unbind();
        statusLabel.managedProperty().unbind();
        optimizePromptButton.disableProperty().unbind();
        saveButton.disableProperty().unbind();
        deleteButton.disableProperty().unbind();
        viewModel = null;
    }
}
