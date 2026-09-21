package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.function.Consumer;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.desktop.component.CopyableTextField;

/** 当前目录行的单份详情编辑器；只对勾选模型开放属性编辑，刷新不重建字段或重置光标。 */
final class ProviderSetupModelDetails extends VBox {
    private final ProviderSetupModelSelection selection;
    private final Runnable changed;
    private final Label heading = new Label("模型属性");
    private final CopyableTextField modelId = new CopyableTextField("未选择模型", "真实模型 ID，只读，可复制");
    private final HBox identifier = field("模型 ID", modelId);
    private final Label selectionHint = new Label();
    private final ComboBox<ProviderSetupPurposeChoice> purpose = new ComboBox<>();
    private final ProviderImageSupportField images = new ProviderImageSupportField();
    private final TextField dimensions = new TextField();
    private final Label empty = new Label("选择模型行查看属性；勾选后将其加入保存列表。");
    private final FlowPane fields;
    private final HBox imageField;
    private final HBox dimensionField;
    private String current = "";
    private boolean rendering;
    private boolean compact;
    private Consumer<Node> focused = ignored -> {};

    ProviderSetupModelDetails(ProviderSetupModelSelection selection, Runnable changed) {
        super(8);
        this.selection = Objects.requireNonNull(selection, "selection");
        this.changed = Objects.requireNonNull(changed, "changed");
        setId("providerWizardModelDetails");
        setMinWidth(0);
        heading.getStyleClass().add("grp-title");
        heading.setId("providerWizardModelHeading");
        modelId.setId("providerWizardCurrentModelId");
        selectionHint.setId("providerWizardModelSelectionHint");
        purpose.setId("providerWizardModelPurpose");
        purpose.setItems(FXCollections.observableArrayList(ProviderSetupPurposeChoice.values()));
        purpose.setConverter(SettingsLabels.converter(ProviderSetupPurposeChoice::label));
        dimensions.setId("providerWizardModelDimensions");
        dimensions.setPromptText("由服务端决定");
        dimensions.setPrefColumnCount(10);
        dimensions.setMaxWidth(160);
        images.setPrefWidth(110);
        images.setMaxWidth(110);
        imageField = field("图片（用户声明）", images);
        images.setTooltip(ProviderSetupChoices.tooltip("图片能力为用户声明，目录读取不会验证该能力。"));
        ((Label) imageField.getChildren().getFirst())
                .setTooltip(ProviderSetupChoices.tooltip("图片能力为用户声明，目录读取不会验证该能力。"));
        images.setAccessibleText("图片输入能力，用户声明，尚未验证");
        dimensionField = field("向量维度", dimensions);
        HBox purposeField = field("用途", purpose);
        fields = new FlowPane(12, 10, purposeField, imageField, dimensionField);
        for (HBox row : new HBox[] {purposeField, imageField, dimensionField}) {
            row.setMinWidth(0);
            row.maxWidthProperty()
                    .bind(Bindings.createDoubleBinding(
                            () -> Math.max(
                                    0,
                                    getWidth()
                                            - getInsets().getLeft()
                                            - getInsets().getRight()),
                            widthProperty(),
                            insetsProperty()));
        }
        fields.setId("providerWizardModelFields");
        for (Label label : new Label[] {selectionHint, empty}) {
            label.setWrapText(true);
            label.setMinWidth(0);
            label.setMinHeight(Region.USE_PREF_SIZE);
        }
        for (Label label : new Label[] {selectionHint, empty}) {
            label.getStyleClass().add("sec-hint");
        }
        heading.setMinWidth(0);
        heading.setMaxWidth(Double.MAX_VALUE);
        modelId.setMinWidth(0);
        HBox.setHgrow(modelId, Priority.ALWAYS);
        getChildren().addAll(heading, empty, identifier, selectionHint, fields);
        bindEdits();
        bind("");
    }

    String currentModel() {
        return current;
    }

    /** 窄窗目录行已显示模型名称，详情只保留可复制 ID 与属性。 */
    void compact(boolean value) {
        compact = value;
        if (!current.isEmpty()) {
            visible(heading, !compact && !selection.model(current).name().equals(current));
        }
    }

    /** 将键盘进入字段的事件交给窄窗详情视口，使聚焦控件滚动到可见区域。 */
    void onFieldFocused(Consumer<Node> listener) {
        focused = Objects.requireNonNull(listener, "listener");
    }

    /** 校验失败后直接回到待完善字段，不创建另一份模型详情。 */
    void focusInvalid() {
        if (selection.model(current).purposes().isEmpty()) {
            purpose.requestFocus();
        } else {
            dimensions.requestFocus();
        }
    }

    void bind(String id) {
        current = Objects.requireNonNullElse(id, "");
        boolean available = !current.isEmpty();
        rendering = true;
        try {
            visible(empty, !available);
            visible(identifier, available);
            visible(selectionHint, available);
            visible(fields, available);
            if (available) {
                renderFields(selection.model(current));
            } else {
                heading.setText("模型属性");
                visible(heading, true);
            }
        } finally {
            rendering = false;
        }
    }

    private void bindEdits() {
        for (Node field : new Node[] {modelId, purpose, images, dimensions}) {
            field.focusedProperty().addListener((ignored, before, value) -> {
                if (value) {
                    focused.accept(field);
                }
            });
        }
        purpose.setOnAction(event -> {
            if (!rendering
                    && purpose.getValue() != null
                    && selection.selected(current)
                    && !selection
                            .model(current)
                            .purposes()
                            .equals(purpose.getValue().purposes())) {
                selection.purposes(current, purpose.getValue().purposes());
                bind(current);
                changed.run();
            }
        });
        images.valueProperty().addListener((ignored, before, value) -> {
            if (!rendering && selection.selected(current)) {
                selection.images(current, value);
                changed.run();
            }
        });
        dimensions.textProperty().addListener((ignored, before, value) -> {
            if (!rendering && selection.selected(current)) {
                selection.dimensions(current, value);
                changed.run();
            }
        });
    }

    private void renderFields(ProviderSetupModelSelection.Model model) {
        heading.setText(model.name());
        visible(heading, !compact && !model.name().equals(model.id()));
        heading.setTooltip(ProviderSetupChoices.tooltip(model.name()));
        if (!modelId.getText().equals(model.id())) {
            modelId.setText(model.id());
            modelId.positionCaret(0);
        }
        modelId.setTooltip(ProviderSetupChoices.tooltip(model.id()));
        boolean included = selection.selected(current);
        selectionHint.setText(!included ? "勾选此模型后可配置用途并保存。" : "待完善：请选择用途后保存。");
        visible(selectionHint, !included || model.purposes().isEmpty());
        visible(fields, included);
        fields.setDisable(!included);
        purpose.setValue(ProviderSetupPurposeChoice.from(model.purposes()));
        images.setValue(model.images());
        // 同一草稿的目录刷新与筛选不能重写文字，避免清除正在编辑的选区。
        if (!dimensions.getText().equals(model.dimensions())) {
            dimensions.setText(model.dimensions());
        }
        visible(imageField, model.purposes().contains(ProviderModelPurpose.CHAT));
        visible(dimensionField, model.purposes().contains(ProviderModelPurpose.EMBEDDING));
    }

    private static HBox field(String text, Region editor) {
        Label label = new Label(text);
        label.setLabelFor(editor);
        return new HBox(6, label, editor);
    }

    private static void visible(Region region, boolean value) {
        region.setVisible(value);
        region.setManaged(value);
    }
}
