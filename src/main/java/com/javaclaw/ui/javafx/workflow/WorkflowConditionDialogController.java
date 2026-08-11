package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.workflow.model.ConditionOperator;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;

/** 条件出口弹窗状态控制器。 */
public final class WorkflowConditionDialogController {

    @FXML private TextField pathField;
    @FXML private ComboBox<ConditionOperator> operatorBox;
    @FXML private TextField valueField;
    @FXML private Spinner<Integer> prioritySpinner;
    @FXML private CheckBox fallbackCheck;

    @FXML
    private void initialize() {
        operatorBox.getItems().setAll(ConditionOperator.values());
        operatorBox.setValue(ConditionOperator.EQUAL);
        prioritySpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(-10_000, 10_000, 0));
        fallbackCheck.selectedProperty().addListener((ignored, previous, selected) -> {
            pathField.setDisable(selected);
            operatorBox.setDisable(selected);
            valueField.setDisable(selected);
        });
    }

    Selection selection() {
        return new Selection(pathField.getText(), operatorBox.getValue(), valueField.getText(),
                prioritySpinner.getValue(), fallbackCheck.isSelected());
    }

    record Selection(
            String path,
            ConditionOperator operator,
            String value,
            int priority,
            boolean fallback) {
        Selection {
            path = path == null ? "" : path.strip();
            operator = operator == null ? ConditionOperator.EQUAL : operator;
            value = value == null ? "" : value.strip();
        }
    }
}
