package com.javaclaw.ui.javafx.skill;

import javafx.fxml.FXML;
import javafx.scene.control.TextField;

/** 新建脚本文件名 FXML 弹窗 Controller。 */
public final class SkillScriptNameDialogController {

    @FXML private TextField nameField;

    @FXML private void initialize() { nameField.setText("script.jsh"); }

    String name() { return nameField.getText() == null ? "" : nameField.getText(); }

    void requestFocus() {
        nameField.requestFocus();
        nameField.selectAll();
    }
}
