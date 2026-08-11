package com.javaclaw.ui.javafx.workflow;

import javafx.fxml.FXML;
import javafx.scene.control.TextArea;

/** 工作流测试输入弹窗 Controller。 */
public final class WorkflowInputDialogController {

    @FXML private TextArea inputArea;

    @FXML
    private void initialize() {
        inputArea.setText("请处理这个输入");
    }

    String input() {
        return inputArea.getText() == null ? "" : inputArea.getText();
    }

    void requestFocus() {
        inputArea.requestFocus();
        inputArea.selectAll();
    }
}
