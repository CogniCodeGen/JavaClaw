package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.geometry.HPos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** 带标题、说明和对齐字段的可复用设置表单分区。 */
public final class FormSection extends VBox {
    private final GridPane fields = new GridPane();
    private int nextRow;

    /**
     * 创建表单分区。
     *
     * @param title 分区标题
     * @param description 简单说明
     */
    public FormSection(String title, String description) {
        Label heading = new Label(requireText(title, "title"));
        heading.getStyleClass().addAll("grp-title", "platform-section-title");
        Label hint = new Label(requireText(description, "description"));
        hint.setWrapText(true);
        hint.getStyleClass().addAll("settings-hint", "platform-section-hint");
        configureColumns();
        fields.getStyleClass().add("platform-form-grid");
        getChildren().addAll(heading, hint, fields);
        getStyleClass().addAll("jc-card", "platform-section-card", "platform-form-section");
    }

    /**
     * 追加一个标签和输入控件。
     *
     * @param label 字段名称
     * @param editor 输入或选择控件
     */
    public void addField(String label, Node editor) {
        Label fieldLabel = new Label(requireText(label, "label"));
        fieldLabel.getStyleClass().add("platform-field-label");
        fields.add(fieldLabel, 0, nextRow);
        fields.add(Objects.requireNonNull(editor, "editor"), 1, nextRow);
        GridPane.setHgrow(editor, Priority.ALWAYS);
        GridPane.setHalignment(fieldLabel, HPos.LEFT);
        nextRow++;
    }

    /**
     * 追加横跨两列的复杂编辑器。
     *
     * @param node 编辑器或说明节点
     */
    public void addFullWidth(Node node) {
        Node checked = Objects.requireNonNull(node, "node");
        fields.add(checked, 0, nextRow, 2, 1);
        GridPane.setHgrow(checked, Priority.ALWAYS);
        nextRow++;
    }

    private void configureColumns() {
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(110);
        labelColumn.setPrefWidth(128);
        ColumnConstraints editorColumn = new ColumnConstraints();
        editorColumn.setHgrow(Priority.ALWAYS);
        editorColumn.setFillWidth(true);
        fields.getColumnConstraints().addAll(labelColumn, editorColumn);
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }
}
