package com.javaclaw.desktop.component;

import java.util.Objects;

import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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
     * <p>该简化入口不显示必填或可选标记，但仍会将 {@link Label#getLabelFor()} 正确指向编辑器。需要完整表单语义时使用 {@link #addRequiredField(String, Node,
     * String)} 或 {@link #addOptionalField(String, Node, String)}。
     *
     * @param label 字段名称
     * @param editor 输入或选择控件
     */
    public void addField(String label, Node editor) {
        addField(label, editor, FieldRequirement.UNMARKED, null);
    }

    /**
     * 追加必填字段，并返回可更新行内错误的句柄。
     *
     * @param label 字段名称
     * @param editor 输入或选择控件
     * @param help 简短帮助文本；{@code null} 或空白表示不显示
     * @return 该字段的行内反馈句柄
     */
    public FieldHandle addRequiredField(String label, Node editor, String help) {
        return addField(label, editor, FieldRequirement.REQUIRED, help);
    }

    /**
     * 追加可选字段，并返回可更新行内错误的句柄。
     *
     * @param label 字段名称
     * @param editor 输入或选择控件
     * @param help 简短帮助文本；{@code null} 或空白表示不显示
     * @return 该字段的行内反馈句柄
     */
    public FieldHandle addOptionalField(String label, Node editor, String help) {
        return addField(label, editor, FieldRequirement.OPTIONAL, help);
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

    private FieldHandle addField(String label, Node editor, FieldRequirement requirement, String help) {
        Node checkedEditor = Objects.requireNonNull(editor, "editor");
        Label fieldLabel = new Label(requireText(label, "label"));
        fieldLabel.setLabelFor(checkedEditor);
        fieldLabel.getStyleClass().add("platform-field-label-text");

        HBox labelRow = new HBox(6, fieldLabel);
        labelRow.setAlignment(Pos.BASELINE_LEFT);
        labelRow.getStyleClass().add("platform-field-label");
        addRequirementMarker(labelRow, Objects.requireNonNull(requirement, "requirement"));

        checkedEditor.getStyleClass().add("platform-field-editor");
        Label helpLabel = feedbackLabel(help, "platform-field-help");
        Label errorLabel = feedbackLabel(null, "platform-field-error");
        VBox editorColumn = new VBox(4, checkedEditor, helpLabel, errorLabel);
        editorColumn.getStyleClass().add("platform-field-content");

        fields.add(labelRow, 0, nextRow);
        fields.add(editorColumn, 1, nextRow);
        GridPane.setHgrow(editorColumn, Priority.ALWAYS);
        GridPane.setHgrow(checkedEditor, Priority.ALWAYS);
        GridPane.setHalignment(labelRow, HPos.LEFT);
        nextRow++;
        return new FieldHandle(errorLabel);
    }

    private static void addRequirementMarker(HBox labelRow, FieldRequirement requirement) {
        if (requirement == FieldRequirement.UNMARKED) {
            return;
        }
        Label marker = new Label(requirement.label());
        marker.getStyleClass().addAll("platform-field-requirement", requirement.cssClass());
        labelRow.getChildren().add(marker);
    }

    private static Label feedbackLabel(String text, String styleClass) {
        Label label = new Label(normalizeOptionalText(text));
        label.setWrapText(true);
        label.setVisible(!label.getText().isEmpty());
        label.managedProperty().bind(label.visibleProperty());
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return checked;
    }

    private enum FieldRequirement {
        UNMARKED("", ""),
        REQUIRED("必填", "platform-field-required"),
        OPTIONAL("可选", "platform-field-optional");

        private final String label;
        private final String cssClass;

        FieldRequirement(String label, String cssClass) {
            this.label = label;
            this.cssClass = cssClass;
        }

        private String label() {
            return label;
        }

        private String cssClass() {
            return cssClass;
        }
    }

    /**
     * 表单字段的行内反馈句柄。
     *
     * <p>句柄只更新展示状态，不执行业务校验；Presenter 应当依据不可变状态投影最新错误。
     */
    public static final class FieldHandle {
        private final Label errorLabel;

        private FieldHandle(Label errorLabel) {
            this.errorLabel = errorLabel;
        }

        /**
         * 更新行内错误；传入 {@code null} 或空白文本时清除错误。
         *
         * @param message 简短中文结论
         */
        public void updateError(String message) {
            String normalized = normalizeOptionalText(message);
            errorLabel.setText(normalized);
            errorLabel.setVisible(!normalized.isEmpty());
        }

        /** 清除当前行内错误。 */
        public void clearError() {
            updateError(null);
        }

        /**
         * 返回当前是否展示行内错误。
         *
         * @return 正在展示错误时为 {@code true}
         */
        public boolean hasError() {
            return errorLabel.isVisible();
        }

        /**
         * 返回已规范化的行内错误文本。
         *
         * @return 无错误时为空字符串
         */
        public String errorMessage() {
            return errorLabel.getText();
        }
    }
}
