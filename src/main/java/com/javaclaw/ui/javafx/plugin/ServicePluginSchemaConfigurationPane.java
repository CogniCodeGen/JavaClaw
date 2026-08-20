package com.javaclaw.ui.javafx.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.ServicePluginInfo;
import com.javaclaw.ui.javafx.control.JsonSchemaForm;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Renders an allow-listed subset of a plugin configuration Schema with host controls only. */
final class ServicePluginSchemaConfigurationPane implements AutoCloseable {
    private final ObjectMapper json;
    private final Consumer<Map<String, String>> save;
    private final VBox root = new VBox(10);
    private final Label validation = new Label();
    private final JsonSchemaForm.Editor editor;
    private final Set<String> fields;
    private final boolean compact;

    ServicePluginSchemaConfigurationPane(
            ServicePluginInfo plugin,
            List<String> requestedFields,
            ObjectMapper json,
            Consumer<Map<String, String>> save) {
        this.json = Objects.requireNonNull(json, "json");
        this.save = Objects.requireNonNull(save, "save");
        try {
            ObjectNode schema = filteredSchema(
                    this.json.readTree(plugin.configurationSchema()), requestedFields);
            fields = Set.copyOf(iterable(schema.path("properties").fieldNames()));
            compact = compactSchema(schema);
            ObjectNode existing = existingValues(schema, plugin.configuration());
            editor = new JsonSchemaForm(this.json).render(
                    schema, JsonNodeFactory.instance.objectNode(), existing, false);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("插件配置 Schema 无效，无法安全编辑", invalid);
        }
        build(plugin);
    }

    Node root() { return root; }

    private void build(ServicePluginInfo plugin) {
        root.getStyleClass().add("service-plugin-schema-form");
        validation.setWrapText(true);
        validation.getStyleClass().add("service-plugin-error");
        validation.setVisible(false);
        validation.setManaged(false);
        Button button = new Button(isRunning(plugin) ? "保存并重启" : "保存配置");
        button.setId("servicePluginSaveSchemaConfiguration");
        button.getStyleClass().addAll("jc-btn", "jc-btn-primary", "jc-btn-sm");
        button.setOnAction(ignored -> submit());
        if (compact) {
            root.getStyleClass().add("service-plugin-schema-form-compact");
            List<Node> fieldNodes = List.copyOf(editor.root().getChildren());
            editor.root().getChildren().clear();
            fieldNodes.forEach(node -> node.getStyleClass().add("service-plugin-compact-field"));
            FlowPane fieldsPane = new FlowPane(12, 6);
            fieldsPane.setPrefWrapLength(560);
            fieldsPane.getStyleClass().add("service-plugin-compact-fields");
            fieldsPane.getChildren().setAll(fieldNodes);
            HBox row = new HBox(12, fieldsPane, spacer(), button);
            row.setAlignment(Pos.BOTTOM_LEFT);
            HBox.setHgrow(fieldsPane, Priority.ALWAYS);
            root.getChildren().addAll(row, validation);
        } else {
            root.getChildren().add(editor.root());
            HBox footer = new HBox(8, validation, spacer(), button);
            footer.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(validation, Priority.ALWAYS);
            root.getChildren().add(footer);
        }
    }

    private static boolean compactSchema(JsonNode schema) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject() || properties.size() == 0 || properties.size() > 3) return false;
        return iterable(properties.elements()).stream().allMatch(property -> switch (
                property.path("type").asText("string")) {
            case "boolean", "integer", "number", "string" -> true;
            default -> false;
        });
    }

    private void submit() {
        try {
            Map<String, String> patch = new LinkedHashMap<>();
            editor.value().fields().forEachRemaining(entry -> {
                if (fields.contains(entry.getKey())) {
                    patch.put(entry.getKey(), entry.getValue().isContainerNode()
                            ? entry.getValue().toString() : entry.getValue().asText());
                }
            });
            validation.setVisible(false);
            validation.setManaged(false);
            save.accept(Map.copyOf(patch));
        } catch (RuntimeException invalid) {
            validation.setText(invalid.getMessage() == null ? "配置无效" : invalid.getMessage());
            validation.setVisible(true);
            validation.setManaged(true);
        }
    }

    private ObjectNode filteredSchema(JsonNode source, List<String> requested) {
        if (source == null || !source.isObject()) {
            throw new IllegalArgumentException("configurationSchema 必须是对象");
        }
        ObjectNode result = source.deepCopy();
        JsonNode original = source.path("properties");
        boolean explicit = requested != null && !requested.isEmpty();
        Set<String> selected = explicit
                ? new LinkedHashSet<>(requested) : new LinkedHashSet<>(iterable(original.fieldNames()));
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        for (String name : selected) {
            JsonNode property = original.get(name);
            if (property == null) throw new IllegalArgumentException("配置字段不存在: " + name);
            if (property.path("x-javaclaw-hidden").asBoolean(false)
                    || property.path("x-javaclaw-host-managed").asBoolean(false)) {
                if (explicit) throw new IllegalArgumentException(
                        "配置字段由宿主管理，不能显示: " + name);
                continue;
            }
            properties.set(name, property.deepCopy());
        }
        result.set("properties", properties);
        JsonNode required = source.get("required");
        if (required != null && required.isArray()) {
            ArrayNode filtered = JsonNodeFactory.instance.arrayNode();
            required.forEach(value -> {
                if (properties.has(value.asText())) filtered.add(value.asText());
            });
            result.set("required", filtered);
        }
        return result;
    }

    private ObjectNode existingValues(JsonNode schema, Map<String, String> values) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (values == null) return result;
        values.forEach((name, raw) -> {
            JsonNode property = schema.path("properties").get(name);
            if (property == null || raw == null) return;
            try {
                result.set(name, switch (property.path("type").asText("string")) {
                    case "boolean" -> JsonNodeFactory.instance.booleanNode(Boolean.parseBoolean(raw));
                    case "integer" -> JsonNodeFactory.instance.numberNode(Long.parseLong(raw));
                    case "number" -> JsonNodeFactory.instance.numberNode(new java.math.BigDecimal(raw));
                    case "object", "array" -> json.readTree(raw);
                    default -> JsonNodeFactory.instance.textNode(raw);
                });
            } catch (Exception ignored) {
                // Registration validates persisted values. Invalid legacy values use Schema defaults.
            }
        });
        return result;
    }

    private static <T> List<T> iterable(java.util.Iterator<T> values) {
        List<T> result = new ArrayList<>();
        values.forEachRemaining(result::add);
        return List.copyOf(result);
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static boolean isRunning(ServicePluginInfo plugin) {
        return plugin.state() == com.javaclaw.application.serviceplugin
                .ServicePluginManagementApplicationService.State.HEALTHY
                || plugin.state() == com.javaclaw.application.serviceplugin
                .ServicePluginManagementApplicationService.State.DEGRADED;
    }

    private static void clearPasswords(Node node) {
        if (node instanceof PasswordField password) password.clear();
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable()
                .forEach(ServicePluginSchemaConfigurationPane::clearPasswords);
    }

    @Override
    public void close() {
        clearPasswords(root);
        validation.setText("");
    }
}
