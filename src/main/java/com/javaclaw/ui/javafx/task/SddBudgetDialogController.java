package com.javaclaw.ui.javafx.task;

import com.javaclaw.application.task.SddTaskApplicationService.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.util.OptionalLong;

/** Token 预算对话框 Controller：只负责数值校验。 */
public final class SddBudgetDialogController {
    @FXML private Label usageLabel;
    @FXML private TextField budgetField;
    @FXML private Label errorLabel;

    private OptionalLong result = OptionalLong.empty();
    private Runnable close = () -> { };

    public void configure(Task task, Runnable close) {
        this.close = close == null ? () -> { } : close;
        usageLabel.setText("已用 " + task.totalTokens() + "（⬆ " + task.totalInputTokens()
                + " + ⬇ " + task.totalOutputTokens() + "）。填 0 表示不限制。");
        budgetField.setText(String.valueOf(task.tokenBudget()));
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        budgetField.requestFocus();
    }

    @FXML
    private void saveRequested() {
        try {
            result = OptionalLong.of(Math.max(0, Long.parseLong(budgetField.getText().strip())));
            close.run();
        } catch (RuntimeException failure) {
            errorLabel.setText("请输入非负整数");
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
            budgetField.requestFocus();
        }
    }

    @FXML private void cancelRequested() { close.run(); }

    OptionalLong result() { return result; }
}
