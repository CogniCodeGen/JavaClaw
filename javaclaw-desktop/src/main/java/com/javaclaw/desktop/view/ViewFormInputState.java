package com.javaclaw.desktop.view;

import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;

import com.javaclaw.extension.spi.ViewFormField;

/** 一个 ViewSchema 表单字段的控件、初值和可见状态。 */
final class ViewFormInputState {
    final ViewFormField field;
    final Label label;
    final Node input;
    final Object initial;

    ViewFormInputState(ViewFormField field, Label label, Node input, Object initial) {
        this.field = field;
        this.label = label;
        this.input = input;
        this.initial = initial;
    }

    void observe(Runnable refresh) {
        InvalidationListener listener = ignored -> refresh.run();
        if (input instanceof CheckBox checkBox) {
            checkBox.selectedProperty().addListener(listener);
        } else if (input instanceof ComboBox<?> comboBox) {
            comboBox.valueProperty().addListener(listener);
        } else if (input instanceof ViewAttachmentFieldControl attachment) {
            attachment.valueProperty().addListener(listener);
            attachment.pendingProperty().addListener(listener);
        } else if (input instanceof ViewStructuredListControl structured) {
            structured.valueProperty().addListener(listener);
        } else {
            ((TextInputControl) input).textProperty().addListener(listener);
        }
    }

    void visible(boolean value) {
        label.setManaged(value);
        label.setVisible(value);
        input.setManaged(value);
        input.setVisible(value);
    }

    boolean visible() {
        return input.isVisible();
    }

    boolean pending() {
        return input instanceof ViewAttachmentFieldControl attachment && attachment.pending();
    }
}
