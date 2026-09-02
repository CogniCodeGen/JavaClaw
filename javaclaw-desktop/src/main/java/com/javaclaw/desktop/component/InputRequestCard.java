package com.javaclaw.desktop.component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.protocol.CanonicalJson;

/**
 * 渲染一个受限 InputRequest，并把用户输入收口为规范 JSON 对象。
 *
 * <p>相同 ID 与 revision 的状态刷新不会重建控件，因此轮询、错误恢复和提交状态变化都不会丢弃尚未提交的草稿。
 */
public final class InputRequestCard extends VBox {
    private final CanonicalJson json;
    private final PlatformComponentFactory components;
    private final BiConsumer<InputRequestRecord, CanonicalPayload> resolution;
    private final Consumer<InputRequestRecord> cancellation;
    private final Label prompt = new Label();
    private final Label metadata = new Label();
    private final Label feedback = new Label();
    private final GridPane fields = new GridPane();
    private final Button cancel;
    private final Button submit;
    private final Map<String, Node> inputs = new LinkedHashMap<>();
    private Optional<InputRequestRecord> current = Optional.empty();
    private Optional<InputResponseSchema> schema = Optional.empty();
    private Optional<String> schemaError = Optional.empty();

    /**
     * 创建输入卡。
     *
     * @param json 规范 JSON codec
     * @param components 509f197 平台组件工厂
     * @param resolution 输入决议动作
     * @param cancellation 取消所属 Turn 动作
     */
    public InputRequestCard(
            CanonicalJson json,
            PlatformComponentFactory components,
            BiConsumer<InputRequestRecord, CanonicalPayload> resolution,
            Consumer<InputRequestRecord> cancellation) {
        this.json = Objects.requireNonNull(json, "json");
        this.components = Objects.requireNonNull(components, "components");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        cancel = components.action("取消所属任务", ActionStyle.DANGER, ActionSize.COMPACT);
        submit = components.action("提交输入", ActionStyle.PRIMARY, ActionSize.COMPACT);
        configure();
    }

    /**
     * 展示权威请求状态；相同 revision 会保留当前控件值。
     *
     * @param request 当前请求
     * @param submitting 是否正在执行提交或取消
     * @param error 最近一次读取或命令错误
     */
    public void render(InputRequestRecord request, boolean submitting, Optional<String> error) {
        InputRequestRecord checked = Objects.requireNonNull(request, "request");
        Optional<String> checkedError = Objects.requireNonNull(error, "error");
        boolean changed = current.filter(value -> sameRevision(value, checked)).isEmpty();
        current = Optional.of(checked);
        if (changed) {
            rebuild(checked);
        }
        fields.setDisable(submitting);
        submit.setDisable(submitting || schema.isEmpty());
        cancel.setDisable(submitting);
        submit.setText(submitting ? "正在提交…" : "提交输入");
        showFeedback(checkedError.or(() -> schemaError));
    }

    /** 清除当前请求及其未提交草稿。 */
    public void clear() {
        current = Optional.empty();
        schema = Optional.empty();
        schemaError = Optional.empty();
        inputs.clear();
        fields.getChildren().clear();
        prompt.setText("");
        metadata.setText("");
        showFeedback(Optional.empty());
    }

    private void configure() {
        getStyleClass().addAll("jc-card", "input-request-card");
        prompt.setWrapText(true);
        prompt.getStyleClass().addAll("grp-title", "input-request-prompt");
        metadata.setWrapText(true);
        metadata.getStyleClass().add("sec-hint");
        fields.getStyleClass().add("platform-form-grid");
        feedback.setWrapText(true);
        feedback.getStyleClass().addAll("sec-hint", "platform-action-error");
        feedback.setManaged(false);
        feedback.setVisible(false);
        submit.setOnAction(event -> submit());
        cancel.setOnAction(event -> cancel());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, feedback, spacer, cancel, submit);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("platform-action-bar");
        getChildren().addAll(prompt, metadata, fields, actions);
        setAccessibleText("等待用户输入");
    }

    private void rebuild(InputRequestRecord record) {
        prompt.setText(record.request().prompt());
        metadata.setText("来源 " + record.request().producerId() + " · 截止 "
                + DateTimeFormatter.ISO_INSTANT.format(record.request().expiresAt()));
        fields.getChildren().clear();
        inputs.clear();
        try {
            InputResponseSchema parsed =
                    InputResponseSchema.parse(json, record.request().responseSchema());
            schema = Optional.of(parsed);
            schemaError = Optional.empty();
            int row = 0;
            for (InputResponseSchema.Field field : parsed.fields()) {
                addField(field, row++);
            }
            showFeedback(Optional.empty());
        } catch (RuntimeException failure) {
            schema = Optional.empty();
            schemaError = Optional.of("输入 Schema 无法安全渲染：" + safeMessage(failure));
            showFeedback(schemaError);
        }
    }

    private void addField(InputResponseSchema.Field field, int row) {
        Label label = new Label(field.label() + (field.required() ? " *" : ""));
        label.getStyleClass().addAll("settings-label", "platform-field-label");
        Node input = input(field);
        input.setAccessibleText(field.label());
        if (input instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(region, Priority.ALWAYS);
        }
        VBox labelBox = new VBox(2, label);
        if (!field.description().isEmpty()) {
            Label description = new Label(field.description());
            description.setWrapText(true);
            description.getStyleClass().add("sec-hint");
            labelBox.getChildren().add(description);
        }
        fields.add(labelBox, 0, row);
        fields.add(input, 1, row);
        inputs.put(field.name(), input);
    }

    private Node input(InputResponseSchema.Field field) {
        if (field.kind() == InputResponseSchema.Kind.BOOLEAN) {
            CheckBox checkBox = new CheckBox("是");
            checkBox.getStyleClass().add("settings-checkbox");
            checkBox.setAllowIndeterminate(!field.required());
            checkBox.setIndeterminate(!field.required());
            return checkBox;
        }
        TextField text = new TextField();
        text.setPromptText(
                switch (field.kind()) {
                    case STRING -> "请输入" + field.label();
                    case NUMBER -> "请输入数值";
                    case INTEGER -> "请输入整数";
                    case BOOLEAN -> throw new IllegalStateException("boolean 使用 CheckBox");
                });
        text.getStyleClass().add("settings-field");
        return text;
    }

    private void submit() {
        try {
            InputRequestRecord request = current.orElseThrow();
            InputResponseSchema currentSchema = schema.orElseThrow();
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            inputs.forEach((name, input) -> values.put(name, value(input)));
            resolution.accept(request, currentSchema.response(values));
            showFeedback(Optional.empty());
        } catch (RuntimeException failure) {
            showFeedback(Optional.of(safeMessage(failure)));
        }
    }

    private void cancel() {
        try {
            cancellation.accept(current.orElseThrow());
        } catch (RuntimeException failure) {
            showFeedback(Optional.of(safeMessage(failure)));
        }
    }

    private static Object value(Node input) {
        if (input instanceof CheckBox checkBox) {
            return checkBox.isIndeterminate() ? null : checkBox.isSelected();
        }
        if (input instanceof TextInputControl text) {
            return text.getText();
        }
        throw new IllegalStateException("未知输入控件");
    }

    private void showFeedback(Optional<String> message) {
        String text =
                message.map(String::strip).filter(value -> !value.isEmpty()).orElse("");
        feedback.setText(text);
        feedback.setManaged(!text.isEmpty());
        feedback.setVisible(!text.isEmpty());
    }

    private static boolean sameRevision(InputRequestRecord left, InputRequestRecord right) {
        return left.request().id().equals(right.request().id()) && left.revision() == right.revision();
    }

    private static String safeMessage(RuntimeException failure) {
        String message = Optional.ofNullable(failure.getMessage())
                .orElse(failure.getClass().getSimpleName());
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
