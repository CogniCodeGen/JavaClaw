package com.javaclaw.ui.javafx.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 通用 JSON Schema Draft 2020-12 对象表单，供 Agent Studio 与本地推理参数共用。 */
public final class JsonSchemaForm {

    private final ObjectMapper json;

    public JsonSchemaForm(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    public Editor render(JsonNode schema, JsonNode uiSchema, JsonNode existing, boolean readOnly) {
        return new Editor(schema, uiSchema, existing, readOnly, json);
    }

    public static final class Editor {
        private final VBox root = new VBox(7);
        private final Map<String, FieldValue> values = new LinkedHashMap<>();

        private Editor(JsonNode schema, JsonNode uiSchema, JsonNode existing,
                       boolean readOnly, ObjectMapper json) {
            JsonNode properties = schema == null ? JsonNodeFactory.instance.objectNode()
                    : schema.path("properties");
            JsonNode ui = uiSchema == null ? JsonNodeFactory.instance.objectNode() : uiSchema;
            properties.fields().forEachRemaining(entry -> addField(entry.getKey(), entry.getValue(),
                    uiFor(ui, entry.getKey()), existing == null ? null : existing.get(entry.getKey()),
                    readOnly, json));
            if (values.isEmpty()) {
                Label empty = new Label("当前 Schema 没有可配置字段");
                empty.getStyleClass().add("settings-hint");
                root.getChildren().add(empty);
            }
        }

        public VBox root() { return root; }

        public JsonNode value() {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            values.forEach((name, field) -> field.write(result, name));
            return result;
        }

        private void addField(String name, JsonNode schema, JsonNode ui, JsonNode existing,
                              boolean readOnly, ObjectMapper json) {
            if (schema.path("x-javaclaw-hidden").asBoolean(false)) return;
            Label label = new Label(schema.path("title").asText(name));
            label.getStyleClass().add("settings-hint");
            FieldValue field = field(schema, ui, existing, json);
            field.node().setDisable(readOnly);
            VBox row = new VBox(4, label, field.node());
            values.put(name, field);
            root.getChildren().add(row);
        }
    }

    private static FieldValue field(
            JsonNode schema, JsonNode ui, JsonNode existing, ObjectMapper json) {
        JsonNode initial = existing != null ? existing : schema.get("default");
        String widget = widget(schema, ui);
        if (schema.path("x-javaclaw-secret").asBoolean(false)
                || schema.path("writeOnly").asBoolean(false)) {
            PasswordField secret = new PasswordField();
            secret.setPromptText(existing == null ? "输入敏感值" : "留空保留现有值");
            return new FieldValue(secret,
                    (target, name) -> target.put(name, secret.getText()));
        }
        List<String> options = options(schema, ui);
        if (!options.isEmpty() || isReferenceWidget(widget)) {
            ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(options));
            box.setMaxWidth(Double.MAX_VALUE);
            box.setEditable(isReferenceWidget(widget));
            box.setPromptText(referencePrompt(widget));
            box.setValue(initial == null ? (options.isEmpty() ? "" : options.getFirst()) : initial.asText());
            return new FieldValue(box, (target, name) -> target.put(name,
                    box.getValue() == null ? "" : box.getValue()));
        }
        if ("password".equals(widget)) {
            throw new IllegalArgumentException("Schema 表单禁止保存密钥值；请使用凭据引用控件");
        }
        return switch (schema.path("type").asText("string")) {
            case "boolean" -> {
                CheckBox box = new CheckBox();
                box.setSelected(initial != null && initial.asBoolean());
                yield new FieldValue(box, (target, name) -> target.put(name, box.isSelected()));
            }
            case "integer" -> {
                int minimum = schema.path("minimum").asInt(Integer.MIN_VALUE / 2);
                int maximum = schema.path("maximum").asInt(Integer.MAX_VALUE / 2);
                int value = initial == null ? Math.max(0, minimum) : initial.asInt();
                Spinner<Integer> spinner = new Spinner<>();
                spinner.setEditable(true);
                spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                        minimum, maximum, Math.max(minimum, Math.min(maximum, value))));
                yield new FieldValue(spinner, (target, name) -> target.put(name, spinner.getValue()));
            }
            case "number" -> {
                TextField number = new TextField(initial == null ? "0" : initial.asText());
                yield new FieldValue(number, (target, name) -> {
                    try { target.put(name, new BigDecimal(number.getText().trim())); }
                    catch (NumberFormatException failure) {
                        throw new IllegalArgumentException(name + " 不是合法数字", failure);
                    }
                });
            }
            case "object", "array" -> {
                String empty = "array".equals(schema.path("type").asText()) ? "[]" : "{}";
                TextArea area = new TextArea(initial == null ? empty : initial.toString());
                area.setPrefRowCount(3);
                yield new FieldValue(area, (target, name) -> {
                    try { target.set(name, json.readTree(area.getText())); }
                    catch (Exception failure) {
                        throw new IllegalArgumentException(name + " 不是合法 JSON", failure);
                    }
                });
            }
            default -> {
                TextField text = new TextField(initial == null ? "" : initial.asText());
                text.setMaxWidth(Double.MAX_VALUE);
                yield new FieldValue(text, (target, name) -> target.put(name, text.getText()));
            }
        };
    }

    private static JsonNode uiFor(JsonNode root, String name) {
        JsonNode properties = root.path("properties").path(name);
        return properties.isMissingNode() ? root.path(name) : properties;
    }

    private static String widget(JsonNode schema, JsonNode ui) {
        String explicit = ui.path(com.javaclaw.framework.spi.AgentStudioUiSchema.WIDGET).asText("").trim();
        return explicit.isEmpty() ? schema.path("format").asText("").trim() : explicit;
    }

    private static boolean isReferenceWidget(String widget) {
        return switch (widget) {
            case com.javaclaw.framework.spi.AgentStudioUiSchema.MODEL_REF,
                 com.javaclaw.framework.spi.AgentStudioUiSchema.TOOL_REF,
                 com.javaclaw.framework.spi.AgentStudioUiSchema.AGENT_REF,
                 com.javaclaw.framework.spi.AgentStudioUiSchema.WORKFLOW_REF,
                 com.javaclaw.framework.spi.AgentStudioUiSchema.SECRET_REF -> true;
            default -> false;
        };
    }

    private static String referencePrompt(String widget) {
        return switch (widget) {
            case com.javaclaw.framework.spi.AgentStudioUiSchema.MODEL_REF -> "选择或输入模型引用";
            case com.javaclaw.framework.spi.AgentStudioUiSchema.TOOL_REF -> "选择或输入工具 ID";
            case com.javaclaw.framework.spi.AgentStudioUiSchema.AGENT_REF -> "选择或输入 Agent ID";
            case com.javaclaw.framework.spi.AgentStudioUiSchema.WORKFLOW_REF -> "选择或输入 Workflow ID";
            case com.javaclaw.framework.spi.AgentStudioUiSchema.SECRET_REF -> "选择凭据引用（不保存密钥值）";
            default -> "";
        };
    }

    private static List<String> options(JsonNode schema, JsonNode ui) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        schema.path("enum").forEach(value -> values.add(value.asText()));
        schema.path("oneOf").forEach(value -> { if (value.has("const")) values.add(value.path("const").asText()); });
        JsonNode configured = ui.path(com.javaclaw.framework.spi.AgentStudioUiSchema.OPTIONS);
        if (configured.isMissingNode()) configured = ui.path("options");
        configured.forEach(value -> values.add(value.isObject()
                ? value.path("value").asText() : value.asText()));
        return List.copyOf(values);
    }

    private record FieldValue(Node node, Writer writer) {
        void write(ObjectNode target, String name) { writer.write(target, name); }
    }
    @FunctionalInterface private interface Writer { void write(ObjectNode target, String name); }
}
