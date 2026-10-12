package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.AddFactCommand;
import javafx.beans.binding.BooleanBinding;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;

import java.util.List;

/** 新增事实弹窗的表单状态。 */
public final class MemoryFactDialogController {
    @FXML private ComboBox<String> section;
    @FXML private TextArea statement;

    void configure(List<String> sections) {
        section.getItems().setAll(sections == null ? List.of() : sections);
        section.setValue(section.getItems().isEmpty() ? "其它" : section.getItems().getFirst());
    }

    BooleanBinding invalidBinding() {
        return statement.textProperty().isEmpty();
    }

    AddFactCommand command() {
        return new AddFactCommand(section.getValue(), statement.getText());
    }

    void focusStatement() { statement.requestFocus(); }
}
