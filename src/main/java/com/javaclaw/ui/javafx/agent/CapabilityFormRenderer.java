package com.javaclaw.ui.javafx.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.CapabilityForm;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.ui.javafx.control.JsonSchemaForm;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent Studio 对通用 JSON Schema 表单增加“启用 Capability”语义。 */
final class CapabilityFormRenderer {
    private final JsonSchemaForm forms;
    private final Map<CapabilityId, CapabilityEditor> editors = new LinkedHashMap<>();

    CapabilityFormRenderer(ObjectMapper json) {
        forms = new JsonSchemaForm(json);
    }

    void render(VBox container, List<CapabilityForm> capabilities,
                Map<CapabilityId, JsonNode> values, boolean readOnly) {
        clear(container);
        for (CapabilityForm form : capabilities) {
            JsonNode existing = values.get(form.id());
            CapabilityEditor editor = new CapabilityEditor(form, existing, readOnly, forms);
            editors.put(form.id(), editor);
            container.getChildren().add(editor.root);
        }
        if (capabilities.isEmpty()) {
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
        private final CheckBox selected = new CheckBox("启用此能力");
        private final VBox root = new VBox(7);
        private final JsonSchemaForm.Editor editor;

        private CapabilityEditor(CapabilityForm form, JsonNode existing,
                                 boolean readOnly, JsonSchemaForm renderer) {
            root.getStyleClass().add("agent-capability-card");
            Label title = new Label(form.displayName());
            title.getStyleClass().add("agent-field-label");
            Label description = new Label(form.description());
            description.setWrapText(true);
            description.getStyleClass().add("settings-hint");
            selected.setSelected(existing != null);
            selected.setDisable(readOnly);
            editor = renderer.render(form.configurationSchema(), form.uiSchema(), existing, readOnly);
            editor.root().disableProperty().bind(selected.selectedProperty().not());
            root.getChildren().addAll(title, description, selected, editor.root());
        }

        private JsonNode value() { return selected.isSelected() ? editor.value() : null; }
    }
}
