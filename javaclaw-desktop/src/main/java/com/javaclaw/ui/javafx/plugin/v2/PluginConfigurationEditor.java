package com.javaclaw.ui.javafx.plugin.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.spi.AgentStudioUiSchema;
import com.javaclaw.ui.javafx.control.JsonSchemaForm;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 宿主配置编辑器；秘密只接受保留、替换与清除三态，刷新运行信息不重建表单。 */
final class PluginConfigurationEditor implements AutoCloseable {
    private static final Set<String> REFERENCE_WIDGETS = Set.of(AgentStudioUiSchema.MODEL_REF,
            AgentStudioUiSchema.TOOL_REF, AgentStudioUiSchema.AGENT_REF,
            AgentStudioUiSchema.WORKFLOW_REF, AgentStudioUiSchema.SECRET_REF);
    enum SecretAction { KEEP, SET, CLEAR }
    record SecretEdit(SecretAction action, String value) { }

    private final ObjectMapper json;
    private final VBox root = new VBox(12);
    private final ReadOnlyBooleanWrapper dirty = new ReadOnlyBooleanWrapper();
    private final Map<String, SecretField> secrets = new LinkedHashMap<>();
    private JsonSchemaForm.Editor form;
    private TextArea raw;
    private ObjectNode initial;
    private long revision;

    PluginConfigurationEditor(ObjectMapper json) { this.json = json; }
    VBox root() { return root; }
    long revision() { return revision; }
    ReadOnlyBooleanProperty dirtyProperty() { return dirty.getReadOnlyProperty(); }
    boolean isDirty() { return dirty.get(); }

    void load(long revision, String schemaJson, String uiSchemaJson, String configurationJson,
              Map<String, Boolean> configuredSecrets) {
        close();
        this.revision = revision;
        initial = object(configurationJson, "配置");
        ObjectNode schema = object(schemaJson, "配置 Schema");
        ObjectNode publicSchema = schema.deepCopy();
        ObjectNode properties = publicSchema.withObject("/properties");
        var secretNames = new java.util.LinkedHashSet<>(configuredSecrets.keySet());
        schema.path("properties").fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.path("writeOnly").asBoolean() || value.path("x-javaclaw-secret").asBoolean()) {
                secretNames.add(entry.getKey());
            }
        });
        secretNames.forEach(name -> { properties.remove(name); initial.remove(name); });
        if (properties.isEmpty()) {
            root.getChildren().add(hint("未声明配置字段时可直接编辑 JSON 对象。秘密在下方单独处理。"));
            raw = new TextArea(initial.toPrettyString());
            raw.setId("pluginConfigurationJson");
            raw.setPrefRowCount(10);
            raw.setWrapText(false);
            raw.textProperty().addListener((ignored, before, after) -> dirty.set(true));
            root.getChildren().add(raw);
        } else {
            form = new JsonSchemaForm(json).render(publicSchema,
                    restrictedUiSchema(object(uiSchemaJson, "配置 UI Schema"), properties), initial, false);
            root.getChildren().add(form.root());
            observe(form.root());
        }
        for (String name : secretNames) {
            JsonNode field = schema.path("properties").path(name);
            SecretField secret = new SecretField(name, field.path("title").asText(name),
                    Boolean.TRUE.equals(configuredSecrets.get(name)));
            secrets.put(name, secret);
            root.getChildren().add(secret.root());
        }
        dirty.set(false);
    }

    /** 仅传递宿主固定控件支持的声明数据，忽略插件脚本、布局和未声明字段。 */
    private ObjectNode restrictedUiSchema(ObjectNode source, ObjectNode properties) {
        ObjectNode restricted = json.createObjectNode();
        ObjectNode fields = restricted.putObject("properties");
        properties.fieldNames().forEachRemaining(name -> {
            JsonNode field = source.path("properties").path(name);
            if (field.isMissingNode()) field = source.path(name);
            ObjectNode target = fields.putObject(name);
            String widget = field.path(AgentStudioUiSchema.WIDGET).asText();
            if (REFERENCE_WIDGETS.contains(widget)) target.put(AgentStudioUiSchema.WIDGET, widget);
            JsonNode options = field.path(AgentStudioUiSchema.OPTIONS);
            if (!options.isArray()) options = field.path("options");
            if (options.isArray()) target.set(AgentStudioUiSchema.OPTIONS, options.deepCopy());
        });
        return restricted;
    }

    String configurationJson() {
        ObjectNode result = initial.deepCopy();
        if (raw != null) result = object(raw.getText(), "配置");
        else if (form != null) { commit(form.root()); result.setAll((ObjectNode) form.value()); }
        for (String name : secrets.keySet()) {
            if (result.has(name)) throw new IllegalArgumentException("请在秘密控件中设置 " + name);
        }
        return result.toString();
    }

    Map<String, SecretEdit> secretChanges() {
        Map<String, SecretEdit> result = new LinkedHashMap<>();
        secrets.forEach((name, field) -> {
            SecretAction action = field.action();
            String value = action == SecretAction.SET ? field.value().getText() : "";
            if (action == SecretAction.SET && value.isBlank()) {
                throw new IllegalArgumentException("替换秘密 " + name + " 时必须输入新值");
            }
            result.put(name, new SecretEdit(action, value));
        });
        return Map.copyOf(result);
    }

    private ObjectNode object(String value, String label) {
        try {
            JsonNode parsed = json.readTree(value == null || value.isBlank() ? "{}" : value);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException(label + "必须为 JSON 对象");
            return (ObjectNode) parsed;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException(label + "不是合法 JSON", failure);
        }
    }

    private void observe(Node node) {
        if (node instanceof TextInputControl input) {
            input.textProperty().addListener((ignored, before, after) -> dirty.set(true));
        } else if (node instanceof CheckBox box) {
            box.selectedProperty().addListener((ignored, before, after) -> dirty.set(true));
        } else if (node instanceof ComboBox<?> box) {
            box.valueProperty().addListener((ignored, before, after) -> dirty.set(true));
            if (box.isEditable()) observe(box.getEditor());
        } else if (node instanceof Spinner<?> spinner) {
            spinner.valueProperty().addListener((ignored, before, after) -> dirty.set(true));
            observe(spinner.getEditor());
        }
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(this::observe);
    }

    private void commit(Node node) {
        if (node instanceof Spinner<?> spinner) spinner.commitValue();
        if (node instanceof ComboBox<?> box && box.isEditable()) {
            commitCombo(box);
        }
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(this::commit);
    }

    private static <T> void commitCombo(ComboBox<T> box) {
        if (box.getConverter() != null) box.setValue(box.getConverter().fromString(box.getEditor().getText()));
    }

    private static Label hint(String text) {
        Label result = new Label(text);
        result.setWrapText(true);
        result.getStyleClass().add("sec-hint");
        return result;
    }

    @Override public void close() {
        secrets.values().forEach(field -> field.value().clear());
        secrets.clear();
        root.getChildren().clear();
        form = null;
        raw = null;
        dirty.set(false);
    }

    private final class SecretField {
        private final ComboBox<String> mode = new ComboBox<>();
        private final PasswordField value = new PasswordField();
        private final VBox root;

        private SecretField(String name, String title, boolean configured) {
            mode.getItems().setAll("保留现有秘密", "替换秘密", "清除秘密");
            mode.getSelectionModel().selectFirst();
            mode.setId("secretMode-" + name);
            value.setId("secretValue-" + name);
            value.setPromptText("输入新秘密，不回显已保存值");
            value.setDisable(true);
            mode.valueProperty().addListener((ignored, before, after) -> {
                value.clear();
                value.setDisable(action() != SecretAction.SET);
                dirty.set(true);
            });
            value.textProperty().addListener((ignored, before, after) -> dirty.set(true));
            root = new VBox(5, new Label(title + (configured ? " · 已配置" : " · 未配置")), mode, value);
        }

        private SecretAction action() { return SecretAction.values()[mode.getSelectionModel().getSelectedIndex()]; }
        private PasswordField value() { return value; }
        private VBox root() { return root; }
    }
}
