package com.javaclaw.desktop.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 可搜索的多选目录；未知用途由用户通过选择对话模型明确确认，搜索不丢失选择。 */
final class ProviderSetupModelForm extends VBox {
    private final TextField search = new TextField();
    private final TextField manual = new TextField();
    private final ComboBox<ProviderModelSpec> current = new ComboBox<>();
    private final VBox rows = new VBox(8);
    private final Map<String, ProviderModelSpec> models = new LinkedHashMap<>();
    private final Map<String, ProviderModelSpec> selected = new LinkedHashMap<>();
    private final Map<String, String> purposeHints = new LinkedHashMap<>();
    private final Label error = new Label();

    ProviderSetupModelForm(PlatformComponentFactory components, Runnable discover) {
        super(12);
        Label instruction = new Label("选择要用于对话的模型；名称不代表能力验证，未知用途请按服务说明确认。");
        instruction.setWrapText(true);
        instruction.getStyleClass().add("sec-hint");
        search.setId("providerWizardSearch");
        search.setPromptText("搜索模型名称或 ID…");
        search.textProperty().addListener((ignored, before, value) -> renderRows());
        Button retry = components.action("重新获取列表", ActionStyle.GHOST, ActionSize.COMPACT);
        retry.setOnAction(event -> discover.run());
        HBox filter = new HBox(8, search, retry);
        HBox.setHgrow(search, Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(180);
        manual.setId("providerWizardManualModel");
        manual.setPromptText("手动输入模型 ID");
        Button add = components.action("添加", ActionStyle.SOFT, ActionSize.COMPACT);
        add.setId("providerWizardAddManual");
        add.setOnAction(event -> addManual());
        manual.setOnAction(event -> addManual());
        HBox input = new HBox(8, manual, add);
        HBox.setHgrow(manual, Priority.ALWAYS);
        current.setId("providerWizardCurrentModel");
        current.setMaxWidth(Double.MAX_VALUE);
        current.setConverter(SettingsLabels.converter(model -> model.displayName() + " · " + model.modelId()));
        error.setWrapText(true);
        error.getStyleClass().add("status-error");
        getChildren().addAll(instruction, filter, scroll, input, error, new Label("当前使用"), current);
    }

    void candidates(List<ProviderModelDiscoveryCandidate> candidates) {
        for (ProviderModelDiscoveryCandidate candidate : candidates) {
            if (candidate.suggestedPurposes().isEmpty()
                    || candidate.suggestedPurposes().contains(ProviderModelPurpose.CHAT)) {
                models.putIfAbsent(candidate.modelId(), chat(candidate.modelId(), candidate.displayName()));
                purposeHints.put(
                        candidate.modelId(), candidate.suggestedPurposes().isEmpty() ? " · 用途未知" : "");
            }
        }
        renderRows();
    }

    List<ProviderModelSpec> selectedModels() {
        return List.copyOf(selected.values());
    }

    String currentModel() {
        return current.getValue() == null ? "" : current.getValue().modelId();
    }

    private void addManual() {
        try {
            String id = manual.getText().strip();
            ProviderModelSpec model = chat(id, id);
            models.putIfAbsent(id, model);
            select(models.get(id), true);
            manual.clear();
            error.setText("");
            renderRows();
        } catch (RuntimeException invalid) {
            error.setText("请输入有效的模型 ID：" + SettingsFailures.message(invalid));
        }
    }

    private void renderRows() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        rows.getChildren().clear();
        models.values().stream()
                .filter(model -> (model.modelId() + " " + model.displayName())
                        .toLowerCase(Locale.ROOT)
                        .contains(query))
                .forEach(model -> {
                    CheckBox choice = new CheckBox(model.displayName() + " · " + model.modelId()
                            + purposeHints.getOrDefault(model.modelId(), ""));
                    choice.setSelected(selected.containsKey(model.modelId()));
                    choice.setOnAction(event -> select(model, choice.isSelected()));
                    rows.getChildren().add(choice);
                });
        if (rows.getChildren().isEmpty()) {
            rows.getChildren().add(new Label("暂无匹配模型，可以在下方手动输入模型 ID。"));
        }
    }

    private void select(ProviderModelSpec model, boolean include) {
        if (include) {
            selected.put(model.modelId(), model);
        } else {
            selected.remove(model.modelId());
        }
        ProviderModelSpec previous = current.getValue();
        current.getItems().setAll(selected.values());
        if (previous != null && selected.containsKey(previous.modelId())) {
            current.setValue(previous);
        } else if (selected.size() == 1) {
            current.setValue(selected.values().iterator().next());
        } else {
            current.setValue(null);
        }
    }

    private static ProviderModelSpec chat(String id, String name) {
        return new ProviderModelSpec(id, name, Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty());
    }
}
