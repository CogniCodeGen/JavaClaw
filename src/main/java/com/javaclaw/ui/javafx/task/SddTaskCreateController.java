package com.javaclaw.ui.javafx.task;

import com.javaclaw.task.TaskNotificationChannel;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** SDD 新建表单 Controller：只校验输入并产生不可变草稿。 */
public final class SddTaskCreateController {

    private static final long CUSTOM_BUDGET = -1;
    private static final String CAP_AUTO = "自动路由（推荐）";
    private static final String CAP_ALL = "全量加载";
    private static final String CAP_CUSTOM = "自定义…";

    @FXML private VBox root;
    @FXML private TextArea descriptionField;
    @FXML private TextField titleField;
    @FXML private TextField directoryField;
    @FXML private ComboBox<String> budgetBox;
    @FXML private TextField customBudgetField;
    @FXML private ComboBox<String> capabilityBox;
    @FXML private TextField customCapabilityField;
    @FXML private ComboBox<String> notificationBox;
    @FXML private Label errorLabel;
    @FXML private Button submitButton;

    private final LinkedHashMap<String, Long> budgets = new LinkedHashMap<>();
    private Consumer<Draft> submit = ignored -> { };
    private Runnable cancel = () -> { };

    @FXML
    private void initialize() {
        budgets.put("120K（推荐）", 120_000L);
        budgets.put("80K", 80_000L);
        budgets.put("200K", 200_000L);
        budgets.put("不限", 0L);
        budgets.put("自定义…", CUSTOM_BUDGET);
        budgetBox.getItems().setAll(budgets.keySet());
        budgetBox.setValue("120K（推荐）");
        capabilityBox.getItems().setAll(CAP_AUTO, CAP_ALL, CAP_CUSTOM);
        capabilityBox.setValue(CAP_AUTO);
        notificationBox.getItems().setAll(TaskNotificationChannel.ORDERED_CHANNELS);
        notificationBox.setValue(TaskNotificationChannel.NONE);
        budgetBox.valueProperty().addListener((ignored, oldValue, value) ->
                visible(customBudgetField, budgets.getOrDefault(value, 0L) == CUSTOM_BUDGET));
        capabilityBox.valueProperty().addListener((ignored, oldValue, value) ->
                visible(customCapabilityField, CAP_CUSTOM.equals(value)));
        visible(customBudgetField, false);
        visible(customCapabilityField, false);
        visible(errorLabel, false);
    }

    public void configure(Consumer<Draft> submit, Runnable cancel) {
        this.submit = submit == null ? ignored -> { } : submit;
        this.cancel = cancel == null ? () -> { } : cancel;
    }

    public void prepare(String description) {
        titleField.clear();
        descriptionField.setText(description == null ? "" : description);
        directoryField.clear();
        budgetBox.setValue("120K（推荐）");
        customBudgetField.setText("120000");
        capabilityBox.setValue(CAP_AUTO);
        customCapabilityField.setText("system,command");
        notificationBox.setValue(TaskNotificationChannel.NONE);
        showError("");
        submitButton.setDisable(false);
        descriptionField.requestFocus();
    }

    public void setBusy(boolean busy) { submitButton.setDisable(busy); }

    @FXML
    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        File selected = chooser.showDialog(root.getScene().getWindow());
        if (selected != null) directoryField.setText(selected.getAbsolutePath());
    }

    @FXML
    private void submitRequested() {
        String description = clean(descriptionField.getText());
        if (description.isEmpty()) {
            showError("请填写任务需求描述");
            descriptionField.requestFocus();
            return;
        }
        Long budget = budget();
        if (budget == null) return;
        showError("");
        submit.accept(new Draft(clean(titleField.getText()), description,
                clean(directoryField.getText()), budget, capabilities(),
                notificationBox.getValue()));
    }

    @FXML private void cancelRequested() { cancel.run(); }

    private Long budget() {
        long preset = budgets.getOrDefault(budgetBox.getValue(), 120_000L);
        if (preset != CUSTOM_BUDGET) return preset;
        try {
            return Math.max(0, Long.parseLong(clean(customBudgetField.getText())));
        } catch (NumberFormatException failure) {
            showError("自定义 Token 预算必须是非负整数");
            customBudgetField.requestFocus();
            return null;
        }
    }

    private String capabilities() {
        if (CAP_ALL.equals(capabilityBox.getValue())) return "all";
        if (!CAP_CUSTOM.equals(capabilityBox.getValue())) return "auto";
        String value = clean(customCapabilityField.getText());
        return value.isEmpty() ? "auto" : value;
    }

    private void showError(String message) {
        errorLabel.setText(message);
        visible(errorLabel, message != null && !message.isBlank());
    }

    private static void visible(javafx.scene.Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    private static String clean(String value) { return value == null ? "" : value.strip(); }

    public record Draft(String title, String description, String workDir,
                        long tokenBudget, String capabilities, String notificationChannel) {}
}
