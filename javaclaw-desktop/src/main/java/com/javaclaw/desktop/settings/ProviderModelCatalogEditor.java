package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.component.PlatformDialogs;

/** 可复用的 Provider 逐模型目录编辑器；远程候选必须经用户确认才会写入草稿。 */
final class ProviderModelCatalogEditor extends VBox {
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final Runnable discover;
    private final Consumer<List<ProviderModelSpec>> modelsChanged;
    private final Consumer<ProviderModelSpec> embeddingSelected;
    private final TableView<ProviderModelSpec> models = new TableView<>();
    private final ComboBox<ProviderModelDiscoveryCandidate> candidates = new ComboBox<>();
    private final Label status = new Label();
    private final Button discoverButton;
    private final Button addManual;
    private final Button addCandidate;
    private final Button edit;
    private final Button remove;
    private final Button bindEmbedding;
    private ProviderModelCatalogState state;
    private boolean canBind;

    ProviderModelCatalogEditor(
            Runnable discover,
            Consumer<List<ProviderModelSpec>> modelsChanged,
            Consumer<ProviderModelSpec> embeddingSelected) {
        this.discover = Objects.requireNonNull(discover, "discover");
        this.modelsChanged = Objects.requireNonNull(modelsChanged, "modelsChanged");
        this.embeddingSelected = Objects.requireNonNull(embeddingSelected, "embeddingSelected");
        discoverButton = action("读取模型目录", ActionStyle.SOFT, event -> discover.run());
        addCandidate = action("添加所选候选", ActionStyle.SOFT, event -> addCandidate());
        edit = action("编辑", ActionStyle.GHOST, event -> editSelected());
        remove = action("移除", ActionStyle.GHOST, event -> removeSelected());
        bindEmbedding = action("设为默认向量模型", ActionStyle.SOFT, event -> bindEmbedding());
        configureTable();
        configureCandidates();
        addManual = action("手工添加模型", ActionStyle.PRIMARY, event -> openEditor(null, null));
        addManual.setId("providerManualModelButton");
        HBox toolbar = new HBox(8, discoverButton, addManual, edit, remove, bindEmbedding);
        HBox candidateRow = new HBox(8, candidates, addCandidate);
        HBox.setHgrow(candidates, Priority.ALWAYS);
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
        getChildren().addAll(toolbar, models, candidateRow, status);
        setSpacing(8);
        VBox.setVgrow(models, Priority.ALWAYS);
    }

    void render(ProviderModelCatalogState value, boolean canDiscover, boolean canEdit, boolean bindingAvailable) {
        state = Objects.requireNonNull(value, "value");
        canBind = bindingAvailable;
        ProviderModelSpec selected = models.getSelectionModel().getSelectedItem();
        models.getItems().setAll(state.models());
        models.refresh();
        if (selected != null) {
            state.models().stream()
                    .filter(model -> model.modelId().equals(selected.modelId()))
                    .findFirst()
                    .ifPresent(model -> models.getSelectionModel().select(model));
        }
        candidates.getItems().setAll(state.candidates());
        candidates.setVisible(!state.candidates().isEmpty());
        candidates.setManaged(!state.candidates().isEmpty());
        addCandidate.setVisible(!state.candidates().isEmpty());
        addCandidate.setManaged(!state.candidates().isEmpty());
        status.setText(state.message());
        discoverButton.setDisable(state.pending() || !canDiscover);
        addManual.setDisable(state.pending() || !canEdit);
        updateSelectionActions(canEdit);
    }

    private void configureTable() {
        models.getStyleClass().add("platform-data-table");
        models.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        models.setPrefHeight(180);
        models.getColumns().add(column("显示名称", ProviderModelSpec::displayName));
        models.getColumns().add(column("真实模型 ID", ProviderModelSpec::modelId));
        models.getColumns().add(column("用途", model -> purposeText(model.purposes())));
        models.getColumns()
                .add(column(
                        "向量维度",
                        model -> model.embeddingDimensions().isPresent()
                                ? Integer.toString(model.embeddingDimensions().getAsInt())
                                : "由服务端决定"));
        models.getColumns().add(column("验证状态", this::validationStatus));
        models.getSelectionModel()
                .selectedItemProperty()
                .addListener((ignored, previous, selected) -> updateSelectionActions(canEdit()));
    }

    private void configureCandidates() {
        candidates.setPromptText("选择远程目录候选");
        candidates.setMaxWidth(Double.MAX_VALUE);
        candidates.setCellFactory(ignored -> components.detailCell(
                ProviderModelDiscoveryCandidate::displayName, ProviderModelDiscoveryCandidate::modelId));
        candidates.setButtonCell(components.textCell(ProviderModelDiscoveryCandidate::displayName));
        candidates.valueProperty().addListener((ignored, previous, selected) -> updateSelectionActions(canEdit()));
    }

    private void addCandidate() {
        ProviderModelDiscoveryCandidate candidate = candidates.getValue();
        if (candidate != null) {
            openEditor(candidate, null);
        }
    }

    private void editSelected() {
        ProviderModelSpec selected = models.getSelectionModel().getSelectedItem();
        if (selected != null) {
            openEditor(selected, selected.modelId());
        }
    }

    private void removeSelected() {
        ProviderModelSpec selected = models.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        ArrayList<ProviderModelSpec> updated = new ArrayList<>(state.models());
        updated.removeIf(model -> model.modelId().equals(selected.modelId()));
        modelsChanged.accept(List.copyOf(updated));
    }

    private void bindEmbedding() {
        ProviderModelSpec selected = models.getSelectionModel().getSelectedItem();
        if (selected != null) {
            embeddingSelected.accept(selected);
        }
    }

    private void openEditor(Object source, String originalId) {
        ModelFields fields = new ModelFields(source, state.adapter());
        Dialog<ProviderModelSpec> dialog = fields.dialog(owner());
        Button save = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        save.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                ProviderModelSpec result = fields.model();
                requireUnique(result.modelId(), originalId);
                fields.error().setText("");
            } catch (RuntimeException invalid) {
                fields.error().setText(SettingsFailures.message(invalid));
                event.consume();
            }
        });
        dialog.setResultConverter(button -> ButtonType.OK.equals(button) ? fields.model() : null);
        dialog.showAndWait().ifPresent(model -> replace(originalId, model));
    }

    private void requireUnique(String modelId, String originalId) {
        boolean duplicate = state.models().stream()
                .anyMatch(model ->
                        model.modelId().equals(modelId) && !model.modelId().equals(originalId));
        if (duplicate) {
            throw new IllegalArgumentException("模型 ID 已存在");
        }
    }

    private void replace(String originalId, ProviderModelSpec model) {
        ArrayList<ProviderModelSpec> updated = new ArrayList<>(state.models());
        if (originalId != null) {
            updated.removeIf(existing -> existing.modelId().equals(originalId));
        }
        updated.add(model);
        updated.sort(Comparator.comparing(ProviderModelSpec::displayName).thenComparing(ProviderModelSpec::modelId));
        modelsChanged.accept(List.copyOf(updated));
    }

    private void updateSelectionActions(boolean canEdit) {
        ProviderModelSpec selected = models.getSelectionModel().getSelectedItem();
        boolean pending = state != null && state.pending();
        edit.setDisable(pending || !canEdit || selected == null);
        remove.setDisable(pending || !canEdit || selected == null);
        addCandidate.setDisable(pending || !canEdit || candidates.getValue() == null);
        bindEmbedding.setDisable(
                pending || !canBind || selected == null || !selected.supports(ProviderModelPurpose.EMBEDDING));
    }

    private boolean canEdit() {
        return !addManual.isDisabled();
    }

    private boolean isDefaultEmbedding(ProviderModelSpec model) {
        if (state == null
                || state.endpoint().isEmpty()
                || state.embeddingBinding().isEmpty()) {
            return false;
        }
        var endpoint = state.endpoint().orElseThrow();
        var reference = state.embeddingBinding().orElseThrow().provider();
        return endpoint.id().equals(reference.endpointId())
                && endpoint.revision() == reference.endpointRevision()
                && model.modelId().equals(reference.model());
    }

    private String validationStatus(ProviderModelSpec model) {
        ArrayList<String> values = new ArrayList<>();
        state.verificationResults().stream()
                .filter(result -> result.provider().model().equals(model.modelId()))
                .forEach(result -> values.add(purposeText(Set.of(result.purpose()))
                        + "测试："
                        + SettingsLabels.providerVerificationState(result.state())));
        state.providerStatus()
                .filter(result -> result.provider().model().equals(model.modelId()))
                .ifPresent(result -> values.add("本地检查：" + SettingsLabels.providerReadiness(result.readiness())));
        if (isDefaultEmbedding(model)) {
            values.add("默认向量模型");
        }
        return values.isEmpty() ? "未验证" : String.join(" · ", values);
    }

    private Button action(String label, ActionStyle style, Consumer<ActionEvent> action) {
        Button button = components.action(label, style, ActionSize.COMPACT);
        button.setOnAction(action::accept);
        return button;
    }

    private Window owner() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private static TableColumn<ProviderModelSpec, String> column(
            String title, java.util.function.Function<ProviderModelSpec, String> value) {
        TableColumn<ProviderModelSpec, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    private static String purposeText(Set<ProviderModelPurpose> purposes) {
        if (purposes.size() == 2) {
            return "对话 / 向量";
        }
        return purposes.contains(ProviderModelPurpose.CHAT) ? "对话" : "向量";
    }

    static String effectiveDisplayName(String modelId, String displayName) {
        String normalizedId = Objects.requireNonNull(modelId, "modelId").strip();
        String normalizedName =
                Objects.requireNonNull(displayName, "displayName").strip();
        return normalizedName.isEmpty() ? normalizedId : normalizedName;
    }

    private static final class ModelFields {
        private final TextField modelId = new TextField();
        private final TextField displayName = new TextField();
        private final CheckBox chat = new CheckBox("对话与工具调用");
        private final CheckBox embedding = new CheckBox("文本向量");
        private final TextField dimensions = new TextField();
        private final Label error = new Label();

        private ModelFields(Object source, ProviderAdapter adapter) {
            if (source instanceof ProviderModelSpec model) {
                initialize(model.modelId(), model.displayName(), model.purposes(), model.embeddingDimensions());
            } else if (source instanceof ProviderModelDiscoveryCandidate candidate) {
                initialize(
                        candidate.modelId(),
                        candidate.displayName(),
                        candidate.suggestedPurposes(),
                        candidate.embeddingDimensions());
            } else {
                initialize("", "", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty());
            }
            boolean chatOnly = adapter == ProviderAdapter.ANTHROPIC || adapter == ProviderAdapter.OPENAI_RESPONSES;
            embedding.setDisable(chatOnly);
            if (chatOnly) {
                embedding.setSelected(false);
                chat.setSelected(true);
            }
            dimensions.disableProperty().bind(embedding.selectedProperty().not());
            error.getStyleClass().add("status-error");
        }

        private Dialog<ProviderModelSpec> dialog(Window owner) {
            Dialog<ProviderModelSpec> dialog = new Dialog<>();
            dialog.setTitle("配置模型");
            dialog.setHeaderText("确认真实模型 ID 及用途");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            FormSection form = new FormSection("模型信息", "用途由用户明确确认，JavaClaw 不会根据模型名称猜测。");
            form.addField("真实模型 ID", modelId);
            form.addField("显示名称", displayName);
            form.addField("用途", new HBox(12, chat, embedding));
            form.addField("向量维度", dimensions);
            form.addFullWidth(error);
            dialog.getDialogPane().setContent(form);
            PlatformDialogs.style(dialog, owner);
            return dialog;
        }

        private ProviderModelSpec model() {
            EnumSet<ProviderModelPurpose> purposes = EnumSet.noneOf(ProviderModelPurpose.class);
            if (chat.isSelected()) {
                purposes.add(ProviderModelPurpose.CHAT);
            }
            if (embedding.isSelected()) {
                purposes.add(ProviderModelPurpose.EMBEDDING);
            }
            OptionalInt parsedDimensions = dimensions.getText().isBlank()
                    ? OptionalInt.empty()
                    : OptionalInt.of(Integer.parseInt(dimensions.getText().strip()));
            return new ProviderModelSpec(
                    modelId.getText(),
                    effectiveDisplayName(modelId.getText(), displayName.getText()),
                    purposes,
                    parsedDimensions);
        }

        private Label error() {
            return error;
        }

        private void initialize(
                String id, String name, Set<ProviderModelPurpose> purposes, OptionalInt embeddingDimensions) {
            modelId.setText(id);
            displayName.setText(name);
            chat.setSelected(purposes.contains(ProviderModelPurpose.CHAT));
            embedding.setSelected(purposes.contains(ProviderModelPurpose.EMBEDDING));
            dimensions.setText(embeddingDimensions.isPresent() ? Integer.toString(embeddingDimensions.getAsInt()) : "");
        }
    }
}
