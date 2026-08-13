package com.javaclaw.ui.javafx.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.CapabilityForm;
import com.javaclaw.framework.api.CapabilityId;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generic JSON-Schema form renderer; installing a capability requires no controller changes. */
final class CapabilityFormRenderer {
    private final ObjectMapper json;
    private final Map<CapabilityId, CapabilityEditor> editors = new LinkedHashMap<>();

    CapabilityFormRenderer(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    void render(
            VBox container,
            List<CapabilityForm> forms,
            Map<CapabilityId, JsonNode> values,
            boolean readOnly) {
        clear(container);
        for (CapabilityForm form : forms) {
            JsonNode existing = values.get(form.id());
            CapabilityEditor editor = new CapabilityEditor(form, existing, readOnly, json);
            editors.put(form.id(), editor);
            container.getChildren().add(editor.root);
        }
        if (forms.isEmpty()) {
            Label empty = new Label("当前没有已安装的 Capability Schema");
            empty.getStyleClass().add("settings-hint");
            container.getChildren().add(empty);
        }
    }

    Map<CapabilityId, JsonNode> values() {
        LinkedHashMap<CapabilityId, JsonNode> result = new LinkedHashMap<>();
        editors.forEach((id, editor) -> {
            JsonNode value = editor.value();
            if (value != null) result.put(id, value);
        });
        return Map.copyOf(result);
    }

    void clear(VBox container) {
        editors.clear();
        if (container != null) container.getChildren().clear();
    }

    private static final class CapabilityEditor {
        private final CapabilityForm form;
        private final CheckBox selected = new CheckBox("启用此能力");
        private final VBox fields = new VBox(7);
        private final VBox root = new VBox(7);
        private final Map<String, FieldValue> values = new LinkedHashMap<>();
        private final ObjectMapper json;

        private CapabilityEditor(
                CapabilityForm form, JsonNode existing, boolean readOnly, ObjectMapper json) {
            this.form = form;
            this.json = json;
            root.getStyleClass().add("agent-capability-card");
            Label title = new Label(form.displayName());
            title.getStyleClass().add("agent-field-label");
            Label description = new Label(form.description());
            description.setWrapText(true);
            description.getStyleClass().add("settings-hint");
            selected.setSelected(existing != null);
            selected.setDisable(readOnly);
            root.getChildren().addAll(title, description, selected, fields);

            JsonNode properties = form.configurationSchema().path("properties");
            properties.fields().forEachRemaining(entry -> addField(
                    entry.getKey(), entry.getValue(), uiFor(form.uiSchema(), entry.getKey()),
                    existing == null ? null : existing.get(entry.getKey()), readOnly));
            fields.disableProperty().bind(selected.selectedProperty().not());
        }

        private void addField(
                String name, JsonNode schema, JsonNode ui,
                JsonNode existing, boolean readOnly) {
            Label label = new Label(schema.path("title").asText(name));
            label.getStyleClass().add("settings-hint");
            FieldValue field = field(schema, ui, existing, json);
            Node node = field.node();
            node.setDisable(readOnly);
            VBox row = new VBox(4, label, node);
            values.put(name, field);
            fields.getChildren().add(row);
        }

        private JsonNode value() {
            if (!selected.isSelected()) return null;
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            values.forEach((name, field) -> field.write(result, name));
            return result;
        }

        private static FieldValue field(
                JsonNode schema, JsonNode ui, JsonNode existing, ObjectMapper json) {
            JsonNode initial = existing != null ? existing : schema.get("default");
            String widget = widget(schema, ui);
            List<String> options = options(schema, ui);
            if (!options.isEmpty() || isReferenceWidget(widget)) {
                ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(options));
                box.setMaxWidth(Double.MAX_VALUE);
                box.setEditable(isReferenceWidget(widget));
                box.setPromptText(referencePrompt(widget));
                box.setValue(initial == null ? (options.isEmpty() ? "" : options.getFirst())
                        : initial.asText());
                return new FieldValue(box, (target, name) -> target.put(
                        name, box.getValue() == null ? "" : box.getValue()));
            }
            if ("password".equals(widget)) {
                throw new IllegalArgumentException(
                        "Agent Definition 禁止保存密钥值；请使用 secret-ref 凭据引用控件");
            }
            return switch (schema.path("type").asText("string")) {
                case "boolean" -> {
                    CheckBox box = new CheckBox();
                    box.setSelected(initial != null && initial.asBoolean());
                    yield new FieldValue(box,
                            (target, name) -> target.put(name, box.isSelected()));
                }
                case "integer" -> {
                    int minimum = schema.path("minimum").asInt(Integer.MIN_VALUE / 2);
                    int maximum = schema.path("maximum").asInt(Integer.MAX_VALUE / 2);
                    int value = initial == null ? Math.max(0, minimum) : initial.asInt();
                    Spinner<Integer> spinner = new Spinner<>();
                    spinner.setEditable(true);
                    spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                            minimum, maximum, Math.max(minimum, Math.min(maximum, value))));
                    yield new FieldValue(spinner, (target, name) ->
                            target.put(name, spinner.getValue()));
                }
                case "number" -> {
                    TextField number = new TextField(initial == null ? "0" : initial.asText());
                    yield new FieldValue(number, (target, name) -> {
                        try {
                            target.put(name, new java.math.BigDecimal(number.getText().trim()));
                        } catch (NumberFormatException failure) {
                            throw new IllegalArgumentException(name + " 不是合法数字", failure);
                        }
                    });
                }
                case "object", "array" -> {
                    String empty = "array".equals(schema.path("type").asText()) ? "[]" : "{}";
                    TextArea area = new TextArea(initial == null ? empty : initial.toString());
                    area.setPrefRowCount(3);
                    yield new FieldValue(area, (target, name) -> {
                        try {
                            target.set(name, json.readTree(area.getText()));
                        } catch (Exception failure) {
                            throw new IllegalArgumentException(name + " 不是合法 JSON", failure);
                        }
                    });
                }
                default -> {
                    TextField text = new TextField(initial == null ? "" : initial.asText());
                    text.setMaxWidth(Double.MAX_VALUE);
                    yield new FieldValue(text,
                            (target, name) -> target.put(name, text.getText()));
                }
            };
        }

        private static JsonNode uiFor(JsonNode root, String name) {
            JsonNode properties = root.path("properties").path(name);
            return properties.isMissingNode() ? root.path(name) : properties;
        }

        private static String widget(JsonNode schema, JsonNode ui) {
            String explicit = ui.path(com.javaclaw.framework.spi.AgentStudioUiSchema.WIDGET)
                    .asText("").trim();
            if (!explicit.isEmpty()) return explicit;
            return schema.path("format").asText("").trim();
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
            schema.path("oneOf").forEach(value -> {
                if (value.has("const")) values.add(value.path("const").asText());
            });
            JsonNode configured = ui.path(com.javaclaw.framework.spi.AgentStudioUiSchema.OPTIONS);
            if (configured.isMissingNode()) configured = ui.path("options");
            configured.forEach(value -> values.add(value.isObject()
                    ? value.path("value").asText() : value.asText()));
            return List.copyOf(values);
        }
    }

    private record FieldValue(Node node, Writer writer) {
        void write(ObjectNode target, String name) { writer.write(target, name); }
    }

    @FunctionalInterface
    private interface Writer { void write(ObjectNode target, String name); }
}
