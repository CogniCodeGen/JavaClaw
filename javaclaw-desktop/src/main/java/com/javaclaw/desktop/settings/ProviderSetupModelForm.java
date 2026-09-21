package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 虚拟化模型目录与单份属性编辑器；筛选只改变可见行，勾选、元数据和未完成输入独立保留。 */
final class ProviderSetupModelForm extends VBox {
    private final ProviderSetupModelSelection selection = new ProviderSetupModelSelection();
    private final ProviderSetupModelDetails details = new ProviderSetupModelDetails(selection, this::edited);
    private final TextField search = new TextField();
    private final TextField manual = new TextField();
    private final ComboBox<String> view = new ComboBox<>();
    private final ComboBox<ProviderModelPurpose> manualPurpose = new ComboBox<>();
    private final ComboBox<ProviderSetupPurposeChoice> batchPurpose = new ComboBox<>();
    private final ListView<String> rows = new ListView<>();
    private final BorderPane directory = new BorderPane();
    private final ScrollPane detailsScroll = new ScrollPane();
    private final Label manualHint = hint("请输入模型 ID 后点击添加。");
    private final Label count = new Label();
    private final Label empty = hint("点击“获取模型”读取目录，也可手动添加模型 ID。");
    private final TitledPane manualSection;
    private final Button manualToggle;
    private final Button batchToggle;
    private final FlowPane batchSection;
    private final FlowPane selectionSummary;
    private Runnable changed = () -> {};
    private boolean rendering;
    private boolean wide;

    ProviderSetupModelForm(PlatformComponentFactory components, Runnable discover) {
        super(8);
        setMinWidth(0);
        manualSection = new TitledPane("手动添加模型", manualContent(components));
        manualSection.setId("providerWizardManualSection");
        manualSection.setAnimated(false);
        manualSection.setExpanded(false);
        manualSection.visibleProperty().bind(manualSection.expandedProperty());
        manualSection.managedProperty().bind(manualSection.expandedProperty());
        manualToggle = components.action("手动添加", ActionStyle.SOFT, ActionSize.COMPACT);
        manual.textProperty().addListener((ignored, before, value) -> manualEdited());
        batchToggle = components.action("批量用途", ActionStyle.GHOST, ActionSize.COMPACT);
        batchToggle.setId("providerWizardBatchToggle");
        batchSection = batchRow(components);
        batchSection.setId("providerWizardBatchSection");
        showBatch(false);
        batchToggle.setOnAction(event -> showBatch(!batchSection.isVisible()));
        configureDirectory();
        selectionSummary = new FlowPane(12, 6, count, batchToggle);
        selectionSummary.setId("providerWizardSelectionSummary");
        selectionSummary.setMinWidth(0);
        selectionSummary.setPrefWrapLength(320);
        count.setId("providerWizardSelectedCount");
        count.setMinWidth(0);
        getChildren().addAll(toolbar(components, discover), selectionSummary, directory, batchSection, manualSection);
        manualSection.expandedProperty().addListener((ignored, before, expanded) -> {
            if (expanded) {
                showBatch(false);
            } else {
                manualToggle.requestFocus();
            }
            updateDirectoryVisibility();
        });
        heightProperty().addListener((ignored, before, value) -> updateDirectoryVisibility());
        widthProperty().addListener((ignored, before, value) -> arrange(value.doubleValue()));
        arrange(0);
        renderRows();
        renderSelection("");
    }

    private FlowPane toolbar(PlatformComponentFactory components, Runnable discover) {
        search.setId("providerWizardSearch");
        search.setPromptText("搜索模型名称或模型 ID");
        search.setAccessibleText("搜索模型名称或模型 ID");
        search.setPrefWidth(240);
        search.setMinWidth(120);
        search.textProperty().addListener((ignored, before, value) -> renderRows());
        view.setId("providerWizardModelFilter");
        view.getItems().setAll("全部模型", "已选模型");
        view.setValue("全部模型");
        view.setAccessibleText("显示全部模型或已选模型");
        view.valueProperty().addListener((ignored, before, value) -> renderRows());
        Button refresh = components.action("获取模型", ActionStyle.GHOST, ActionSize.COMPACT);
        refresh.setId("providerWizardDiscoverModels");
        refresh.setOnAction(event -> discover.run());
        manualToggle.setId("providerWizardShowManual");
        manualToggle.setOnAction(event -> {
            manualSection.setExpanded(true);
            manual.requestFocus();
        });
        return new FlowPane(8, 8, search, view, refresh, manualToggle);
    }

    private void configureDirectory() {
        rows.setId("providerWizardModelsList");
        rows.getStyleClass().add("platform-data-list");
        rows.setAccessibleText("模型目录；选中行查看属性，勾选后加入保存列表");
        rows.setMinWidth(0);
        rows.setMinHeight(110);
        rows.setPrefHeight(250);
        rows.setPlaceholder(empty);
        rows.setCellFactory(ignored -> new ProviderSetupModelCell(selection, this::include));
        rows.setOnKeyPressed(event -> {
            String id = rows.getSelectionModel().getSelectedItem();
            if (event.getTarget() == rows && event.getCode() == KeyCode.SPACE && id != null) {
                include(id, !selection.selected(id));
                event.consume();
            }
        });
        rows.getSelectionModel().selectedItemProperty().addListener((ignored, before, id) -> {
            if (!rendering && id != null) {
                details.bind(id);
            }
        });
        directory.setId("providerWizardDirectory");
        directory.setMinSize(0, 0);
        directory.setCenter(rows);
        VBox.setVgrow(directory, Priority.ALWAYS);
        configureDetailsViewport();
    }

    private void configureDetailsViewport() {
        detailsScroll.setId("providerWizardModelDetailsScroll");
        detailsScroll.setFitToWidth(true);
        detailsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailsScroll.setMinSize(0, 64);
        detailsScroll.setMaxHeight(130);
        detailsScroll
                .prefViewportHeightProperty()
                .bind(Bindings.createDoubleBinding(
                        () -> Math.max(64, Math.min(130, directory.getHeight() - rows.getMinHeight())),
                        directory.heightProperty(),
                        rows.minHeightProperty()));
        detailsScroll.prefHeightProperty().bind(detailsScroll.prefViewportHeightProperty());
        details.onFieldFocused(this::scrollToField);
    }

    private void arrange(double width) {
        boolean nextWide = width >= 760;
        boolean arranged = directory.getBottom() == detailsScroll || directory.getRight() == details;
        if (arranged && nextWide == wide) {
            return;
        }
        wide = nextWide;
        directory.setBottom(null);
        directory.setRight(null);
        detailsScroll.setContent(null);
        details.compact(!wide);
        details.getStyleClass().remove("platform-detail-pane");
        if (wide) {
            details.setPrefWidth(290);
            details.getStyleClass().add("platform-detail-pane");
            directory.setRight(details);
        } else {
            details.setPrefWidth(Region.USE_COMPUTED_SIZE);
            detailsScroll.setContent(details);
            directory.setBottom(detailsScroll);
        }
    }

    private void scrollToField(Node field) {
        if (wide || detailsScroll.getContent() != details) {
            return;
        }
        detailsScroll.applyCss();
        detailsScroll.layout();
        Bounds bounds = details.sceneToLocal(field.localToScene(field.getBoundsInLocal()));
        double viewport = detailsScroll.getViewportBounds().getHeight();
        double available = details.getHeight() - viewport;
        if (available <= 0) {
            return;
        }
        double current = available * detailsScroll.getVvalue();
        double next = bounds.getMinY() < current ? bounds.getMinY() : Math.max(current, bounds.getMaxY() - viewport);
        detailsScroll.setVvalue(Math.max(0, Math.min(1, next / available)));
    }

    private VBox manualContent(PlatformComponentFactory components) {
        manualHint.setId("providerWizardManualHint");
        manual.setId("providerWizardManualModel");
        manual.setPromptText("输入完整模型 ID");
        manual.setPrefWidth(240);
        manual.setMinWidth(120);
        manualPurpose.setItems(FXCollections.observableArrayList(ProviderModelPurpose.values()));
        manualPurpose.setConverter(SettingsLabels.converter(value -> value == ProviderModelPurpose.CHAT ? "对话" : "向量"));
        manualPurpose.setValue(ProviderModelPurpose.CHAT);
        manualPurpose.setId("providerWizardManualPurpose");
        Button add = components.action("添加", ActionStyle.SOFT, ActionSize.COMPACT);
        add.setId("providerWizardAddManual");
        add.disableProperty()
                .bind(Bindings.createBooleanBinding(() -> manual.getText().isBlank(), manual.textProperty()));
        add.setOnAction(event -> addManual());
        manual.setOnAction(event -> {
            event.consume();
            addManual();
        });
        return new VBox(6, new FlowPane(8, 8, manual, manualPurpose, add), manualHint);
    }

    private FlowPane batchRow(PlatformComponentFactory components) {
        batchPurpose.setItems(FXCollections.observableArrayList(
                ProviderSetupPurposeChoice.CHAT,
                ProviderSetupPurposeChoice.EMBEDDING,
                ProviderSetupPurposeChoice.BOTH));
        batchPurpose.setConverter(SettingsLabels.converter(ProviderSetupPurposeChoice::label));
        batchPurpose.setPromptText("选择批量用途");
        batchPurpose.setId("providerWizardBatchPurpose");
        Button apply = components.action("应用到全部已选模型", ActionStyle.SOFT, ActionSize.COMPACT);
        apply.setId("providerWizardApplyPurpose");
        apply.disableProperty().bind(batchPurpose.valueProperty().isNull());
        apply.setOnAction(event -> {
            boolean modifies = selection.selectedDrafts().stream()
                    .anyMatch(model ->
                            !model.purposes().equals(batchPurpose.getValue().purposes()));
            showBatch(false);
            rows.requestFocus();
            if (!modifies) {
                return;
            }
            selection.selectedPurposes(batchPurpose.getValue().purposes());
            details.bind(details.currentModel());
            edited();
        });
        return new FlowPane(8, 8, batchPurpose, apply);
    }

    void onChanged(Runnable value) {
        changed = Objects.requireNonNull(value, "value");
    }

    /** 将稳定的计数及批量入口交给内嵌编辑器标题；未调用的兼容向导仍在表单内显示。 */
    Node selectionSummary() {
        getChildren().remove(selectionSummary);
        return selectionSummary;
    }

    void seed(List<ProviderModelSpec> models) {
        selection.seed(models);
        renderRows();
        renderSelection(models.isEmpty() ? "" : models.getFirst().modelId());
    }

    void candidates(List<ProviderModelDiscoveryCandidate> candidates) {
        selection.candidates(candidates);
        renderRows();
        renderSelection(details.currentModel());
    }

    /** 连接目标变化时移除旧候选，保留已选属性和仍待确认的手动输入。 */
    void connectionChanged() {
        selection.clearCandidates();
        String current = details.currentModel();
        if (!selection.selected(current)) {
            current = selection.selectedDrafts().stream()
                    .map(ProviderSetupModelSelection.Model::id)
                    .findFirst()
                    .orElse("");
        }
        details.bind(current);
        renderRows();
        renderSelection(current);
    }

    List<ProviderModelSpec> selectedModels() {
        if (!manual.getText().isBlank()) {
            throw new IllegalArgumentException("请展开“手动添加模型”并点击“添加”确认输入，或清空该输入。");
        }
        return selection.selectedModels();
    }

    String currentModel() {
        return details.currentModel();
    }

    void focusModel(String id) {
        selection.model(id);
        renderSelection(id);
        if (rows.getItems().contains(id)) {
            rows.scrollTo(id);
        }
    }

    /** 使尚未确认的手动输入或第一个无效模型可见，并聚焦到需要修正的字段。 */
    void focusFirstInvalid() {
        if (!manual.getText().isBlank()) {
            manualSection.setExpanded(true);
            manual.requestFocus();
            return;
        }
        for (ProviderSetupModelSelection.Model model : selection.selectedDrafts()) {
            try {
                model.toSpec();
            } catch (IllegalArgumentException invalid) {
                showBatch(false);
                manualSection.setExpanded(false);
                if (!rows.getItems().contains(model.id())) {
                    search.clear();
                }
                focusModel(model.id());
                details.focusInvalid();
                return;
            }
        }
    }

    private void include(String id, boolean include) {
        if (selection.selected(id) == include) {
            return;
        }
        selection.select(id, include);
        renderRows();
        renderSelection(id);
        edited();
    }

    private void manualEdited() {
        manualSection.setText(manual.getText().isBlank() ? "手动添加模型" : "手动添加模型（有待确认输入）");
        manualToggle.setText(manual.getText().isBlank() ? "手动添加" : "手动添加（待确认）");
        manualHint.setText("请输入模型 ID 后点击添加。");
        edited();
    }

    private void addManual() {
        String id = manual.getText().strip();
        if (id.isEmpty()) {
            manualHint.setText("请输入模型 ID 后点击添加。");
            return;
        }
        try {
            selection.addManual(id, manualPurpose.getValue());
            manual.clear();
            search.clear();
            manualHint.setText("模型已加入保存列表，可继续调整属性。");
            manualSection.setExpanded(false);
            renderRows();
            focusModel(id);
            rows.requestFocus();
            edited();
        } catch (RuntimeException failure) {
            manualHint.setText("无法添加模型：" + SettingsFailures.message(failure));
        }
    }

    private void renderRows() {
        boolean selectedOnly = "已选模型".equals(view.getValue());
        List<String> visible = selection.matching(search.getText()).stream()
                .map(ProviderSetupModelSelection.Model::id)
                .filter(id -> !selectedOnly || selection.selected(id))
                .toList();
        rendering = true;
        try {
            // 列表仅保存稳定 ID；筛选暂时隐藏焦点行时，右侧草稿和输入选区不受影响。
            if (!rows.getItems().equals(visible)) {
                rows.getItems().setAll(visible);
            }
            selectVisibleRow(details.currentModel());
            rows.refresh();
            empty.setText(emptyMessage(selectedOnly));
        } finally {
            rendering = false;
        }
    }

    private String emptyMessage(boolean selectedOnly) {
        if (!search.getText().isBlank()) {
            return "没有匹配的模型。更换搜索词，或手动添加完整模型 ID。";
        }
        return selectedOnly ? "尚未选择模型。切换到“全部模型”勾选，或手动添加。" : "点击“获取模型”读取目录，也可手动添加模型 ID。";
    }

    private void renderSelection(String focusId) {
        renderCount();
        String id = focusId;
        if (id.isEmpty() && !rows.getItems().isEmpty()) {
            id = rows.getItems().getFirst();
        }
        details.bind(id);
        rendering = true;
        try {
            selectVisibleRow(id);
        } finally {
            rendering = false;
        }
    }

    private void selectVisibleRow(String id) {
        if (rows.getItems().contains(id)) {
            rows.getSelectionModel().select(id);
        } else {
            rows.getSelectionModel().clearSelection();
        }
    }

    private void renderCount() {
        long incomplete = selection.selectedDrafts().stream()
                .filter(model -> model.purposes().isEmpty())
                .count();
        count.setText(
                "已选 " + selection.selectedCount() + " 个模型" + (incomplete > 0 ? " · " + incomplete + " 个待完善用途" : ""));
        boolean multiple = selection.selectedCount() > 1;
        batchToggle.setVisible(multiple);
        batchToggle.setManaged(multiple);
        if (!multiple) {
            showBatch(false);
        }
    }

    private void showBatch(boolean visible) {
        if (visible) {
            manualSection.setExpanded(false);
        }
        batchSection.setVisible(visible);
        batchSection.setManaged(visible);
        updateDirectoryVisibility();
    }

    /** 紧凑视口用同一空间完成辅助编辑；暂时隐藏目录不销毁选择、详情或输入。 */
    private void updateDirectoryVisibility() {
        boolean visible = getHeight() >= 360 || !manualSection.isExpanded() && !batchSection.isVisible();
        directory.setVisible(visible);
        directory.setManaged(visible);
    }

    private void edited() {
        if (batchToggle != null) {
            renderCount();
            rows.refresh();
        }
        changed.run();
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMinWidth(0);
        label.getStyleClass().add("sec-hint");
        return label;
    }
}
