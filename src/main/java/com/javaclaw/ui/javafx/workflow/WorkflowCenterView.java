package com.javaclaw.ui.javafx.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.EdgeKind;
import com.javaclaw.workflow.model.ConditionOperator;
import com.javaclaw.workflow.model.ConditionRule;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.GraphKind;
import com.javaclaw.workflow.model.NodeDefinition;
import com.javaclaw.workflow.model.NodeType;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.model.ResumeSafety;
import com.javaclaw.workflow.model.RetryPolicy;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.runtime.ValidationIssue;
import com.javaclaw.workflow.service.WorkflowService;
import com.javaclaw.workflow.store.WorkflowDefinitionRecord;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 纯 JavaFX 工作流定义、发布与调试中心。 */
public final class WorkflowCenterView {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Stage owner;
    private final WorkflowService service;
    private final Consumer<WorkflowDefinitionRecord> onPublished;
    private final Stage stage = new Stage();
    private final ListView<Item> list = new ListView<>();
    private final GraphPane graphPane = new GraphPane();
    private final TextArea configEditor = new TextArea();
    private final TextField nodeLabel = new TextField();
    private final Spinner<Integer> retryAttempts = new Spinner<>(1, 10, 1);
    private final Spinner<Integer> retryBackoff = new Spinner<>(0, 300_000, 0, 250);
    private final ComboBox<ResumeSafety> resumeSafety = new ComboBox<>();
    private final TextArea console = new TextArea();
    private final ListView<GraphRun> runHistory = new ListView<>();
    private final TextField resumeInput = new TextField();
    private final Label title = new Label("工作流中心");
    private final Label titleHint = new Label("选择一个工作流开始编排");
    private final Label dirtyLabel = new Label();
    private final Label inspectorTitle = new Label("未选择节点");
    private final Label inspectorType = new Label("在画布中选择节点后配置");
    private final Label zoomLabel = new Label("100%");
    private final Label canvasHint = new Label("拖拽节点调整流程 · 右键节点创建连线");
    private final MenuButton nodePalette = new MenuButton("＋ 添加节点");
    private final StackPane inspectorBody = new StackPane();
    private final VBox inspectorActions = new VBox();
    private final StackPane dockBody = new StackPane();
    private final PauseTransition autosave = new PauseTransition(Duration.millis(500));
    private WorkflowEditorModel editor;
    private NodeDefinition selectedNode;
    private boolean readOnly;
    private boolean dirty;
    private boolean restoringSelection;
    private boolean runtimeClosing;
    private double zoom = 1.0;
    private Button publishButton;
    private Button testButton;

    public WorkflowCenterView(
            Stage owner,
            WorkflowService service,
            Consumer<WorkflowDefinitionRecord> onPublished) {
        this.owner = owner;
        this.service = service;
        this.onPublished = onPublished == null ? ignored -> {} : onPublished;
        build();
    }

    public void show() { reloadList(null); stage.show(); stage.toFront(); }
    public boolean isShowing() { return stage.isShowing(); }

    /** 在工作区运行时关闭前同步保存草稿并关闭窗口。 */
    public void close() {
        if (Platform.isFxApplicationThread()) {
            closeForRuntime();
            return;
        }
        CountDownLatch closed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { closeForRuntime(); }
            finally { closed.countDown(); }
        });
        try {
            if (!closed.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待工作流中心关闭超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待工作流中心关闭被中断", e);
        }
    }

    private void build() {
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("JavaClaw · 工作流中心");
        HBox editorRow = new HBox(canvasViewport(), inspector());
        HBox.setHgrow(editorRow.getChildren().getFirst(), Priority.ALWAYS);
        VBox.setVgrow(editorRow, Priority.ALWAYS);

        VBox workspace = new VBox(toolbar(), editorRow, consolePane(), footer());
        VBox.setVgrow(editorRow, Priority.ALWAYS);
        HBox.setHgrow(workspace, Priority.ALWAYS);

        HBox root = new HBox(leftPane(), workspace);
        root.getStyleClass().addAll("root", "workflow-center");
        Scene scene = new Scene(root, 1320, 820);
        addCss(scene, "/css/chat.css");
        addCss(scene, "/css/workflow-center.css");
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && graphPane.connectSource != null) {
                cancelConnection(); event.consume();
            }
        });
        stage.setScene(scene);
        stage.setMinWidth(1080);
        stage.setMinHeight(700);
        autosave.setOnFinished(e -> saveDraft());
        // 覆盖标题栏关闭、代码调用 close() 与窗口隐藏，确保最后一次防抖编辑不会丢失。
        stage.setOnCloseRequest(e -> {
            if (!runtimeClosing && !persistPendingDraft()) e.consume();
        });
    }

    private void closeForRuntime() {
        runtimeClosing = true;
        try {
            autosave.stop();
            if (!persistPendingDraft()) {
                log("草稿保存失败；工作区运行时正在关闭，编辑窗口已停用");
            }
            stage.close();
            if (stage.isShowing()) stage.hide();
        } finally {
            runtimeClosing = false;
        }
    }

    private HBox toolbar() {
        title.getStyleClass().add("workflow-title"); titleHint.getStyleClass().add("workflow-title-hint");
        dirtyLabel.getStyleClass().addAll("jc-badge", "workflow-save-badge");
        VBox titleBox = new VBox(3, new HBox(8, title, dirtyLabel), titleHint);
        nodePalette.getStyleClass().addAll("jc-btn", "jc-btn-soft", "workflow-add-node");
        for (NodeType type : List.of(NodeType.AGENT, NodeType.TOOL, NodeType.CONDITION,
                NodeType.TRANSFORM, NodeType.HUMAN_INPUT, NodeType.OUTPUT)) {
            MenuItem item = new MenuItem(nodeGlyph(type) + "  " + nodeDisplayName(type));
            item.setOnAction(e -> {
                if (editor != null && !readOnly) {
                    int count = (int) editor.current().nodes().stream()
                            .filter(n -> n.type() != NodeType.START && n.type() != NodeType.END).count();
                    editor.addNode(type, 310 + (count % 2) * 230, 240 + (count / 2) * 130);
                    changed(true);
                }
            });
            nodePalette.getItems().add(item);
        }
        Button clone = styledButton("⧉ 复制为草稿", "jc-btn-ghost", this::cloneSelected);
        Button undo = styledButton("↶", "jc-btn-ghost", () -> { if (editor != null && !readOnly) { editor.undo(); changed(true); } });
        Button redo = styledButton("↷", "jc-btn-ghost", () -> { if (editor != null && !readOnly) { editor.redo(); changed(true); } });
        undo.getStyleClass().add("workflow-icon-btn"); redo.getStyleClass().add("workflow-icon-btn");
        Tooltip.install(undo, new Tooltip("撤销")); Tooltip.install(redo, new Tooltip("重做"));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, titleBox, spacer, undo, redo, clone, nodePalette);
        bar.setAlignment(Pos.CENTER_LEFT); bar.getStyleClass().add("workflow-toolbar");
        return bar;
    }

    private VBox leftPane() {
        Label glyph = new Label("⌘"); glyph.getStyleClass().add("workflow-brand-glyph");
        Label label = new Label("工作流中心"); label.getStyleClass().add("modal-left-title");
        Label hint = new Label("本地状态图 · 自动保存"); hint.getStyleClass().add("workflow-left-hint");
        VBox headings = new VBox(3, new HBox(8, glyph, label), hint);
        headings.getStyleClass().add("workflow-left-head");
        Button create = styledButton("＋ 新建工作流", "jc-btn-primary", this::createNew);
        create.setMaxWidth(Double.MAX_VALUE);
        HBox createRow = new HBox(create); createRow.getStyleClass().add("workflow-create-row");
        HBox.setHgrow(create, Priority.ALWAYS);
        Label group = new Label("工作流"); group.getStyleClass().add("modal-nav-group");
        list.setPrefWidth(258);
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) { setGraphic(null); return; }
                Label icon = new Label(item.system ? "◆" : "◇"); icon.getStyleClass().add("workflow-list-icon");
                Label name = new Label(item.name); name.getStyleClass().add("workflow-list-name");
                Label badge = new Label(item.system ? "系统" : item.published ? "已发布" : "草稿");
                badge.getStyleClass().addAll("jc-badge", item.system ? "workflow-badge-system"
                        : item.published ? "jc-badge-ok" : "jc-badge-stopped");
                Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
                HBox row = new HBox(8, icon, name, gap, badge); row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        list.getStyleClass().add("workflow-list");
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (restoringSelection || select(item)) return;
            restoringSelection = true;
            list.getSelectionModel().select(old);
            restoringSelection = false;
        });
        VBox box = new VBox(headings, createRow, group, list);
        box.getStyleClass().addAll("modal-left-pane", "workflow-left");
        VBox.setVgrow(list, Priority.ALWAYS); return box;
    }

    private VBox inspector() {
        Label eyebrow = new Label("属性检查器"); eyebrow.getStyleClass().add("workflow-eyebrow");
        inspectorTitle.getStyleClass().add("workflow-inspector-title");
        inspectorType.getStyleClass().addAll("jc-badge", "jc-badge-stopped");
        VBox heading = new VBox(6, eyebrow, new HBox(8, inspectorTitle, inspectorType));
        heading.getStyleClass().add("workflow-inspector-head");
        nodeLabel.setPromptText("节点名称");
        resumeSafety.getItems().setAll(ResumeSafety.values());
        configEditor.setWrapText(false); configEditor.setPromptText("选择节点后编辑 JSON 配置");
        nodeLabel.getStyleClass().add("workflow-field");
        configEditor.getStyleClass().add("workflow-json-editor");
        resumeSafety.getStyleClass().add("workflow-field");
        retryAttempts.getStyleClass().add("workflow-spinner"); retryBackoff.getStyleClass().add("workflow-spinner");

        Label emptyIcon = new Label("◎"); emptyIcon.getStyleClass().add("workflow-inspector-empty-icon");
        Label emptyText = new Label("选择一个节点"); emptyText.getStyleClass().add("workflow-empty-title");
        Label emptyHint = new Label("查看配置、调整重试策略，或创建新的出口连线");
        emptyHint.getStyleClass().add("sec-hint"); emptyHint.setWrapText(true); emptyHint.setMaxWidth(230);
        VBox empty = new VBox(10, emptyIcon, emptyText, emptyHint); empty.setAlignment(Pos.CENTER);

        GridPane policy = new GridPane(); policy.setHgap(10); policy.setVgap(10);
        policy.add(fieldLabel("节点名称"), 0, 0); policy.add(nodeLabel, 1, 0);
        policy.add(fieldLabel("尝试次数"), 0, 1); policy.add(retryAttempts, 1, 1);
        policy.add(fieldLabel("退避时间"), 0, 2); policy.add(retryBackoff, 1, 2);
        policy.add(fieldLabel("恢复安全"), 0, 3); policy.add(resumeSafety, 1, 3);
        ColumnConstraints labelCol = new ColumnConstraints(72);
        ColumnConstraints fieldCol = new ColumnConstraints(); fieldCol.setHgrow(Priority.ALWAYS); fieldCol.setFillWidth(true);
        policy.getColumnConstraints().addAll(labelCol, fieldCol);
        GridPane.setHgrow(nodeLabel, Priority.ALWAYS); GridPane.setHgrow(resumeSafety, Priority.ALWAYS);
        nodeLabel.setMaxWidth(Double.MAX_VALUE); resumeSafety.setMaxWidth(Double.MAX_VALUE);
        Label jsonLabel = new Label("节点配置"); jsonLabel.getStyleClass().add("grp-title");
        VBox form = new VBox(10, policy, jsonLabel, configEditor);
        VBox.setVgrow(configEditor, Priority.ALWAYS); form.getStyleClass().add("workflow-inspector-form");
        inspectorBody.getChildren().addAll(empty, form); form.setVisible(false); form.setManaged(false);
        VBox.setVgrow(inspectorBody, Priority.ALWAYS);

        Button apply = styledButton("应用更改", "jc-btn-primary", this::applyNodeConfig);
        Button connect = styledButton("创建出口", "jc-btn-soft", () -> {
            if (selectedNode != null) beginConnection(selectedNode, EdgeKind.NORMAL);
        });
        Button errorEdge = styledButton("错误出口", "jc-btn-ghost", () -> {
            if (selectedNode != null) beginConnection(selectedNode, EdgeKind.ERROR);
        });
        Button delete = styledButton("删除", "jc-btn-danger", () -> {
            if (editor != null && selectedNode != null && !readOnly) {
                if (selectedNode.type() == NodeType.END) {
                    log("END 节点是工作流必需出口，不能删除");
                    return;
                }
                try { editor.deleteNode(selectedNode.id()); selectedNode = null; showInspector(null); changed(true); }
                catch (Exception ex) { log(ex.getMessage()); }
            }
        });
        apply.setMaxWidth(Double.MAX_VALUE); connect.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(apply, Priority.ALWAYS); HBox.setHgrow(connect, Priority.ALWAYS);
        HBox primaryActions = new HBox(8, apply, connect);
        HBox secondaryActions = new HBox(8, errorEdge, delete);
        HBox.setHgrow(errorEdge, Priority.ALWAYS); HBox.setHgrow(delete, Priority.ALWAYS);
        errorEdge.setMaxWidth(Double.MAX_VALUE); delete.setMaxWidth(Double.MAX_VALUE);
        inspectorActions.getChildren().setAll(primaryActions, secondaryActions);
        inspectorActions.setSpacing(8); inspectorActions.getStyleClass().add("workflow-inspector-actions");
        inspectorActions.setVisible(false); inspectorActions.setManaged(false);
        VBox box = new VBox(heading, inspectorBody, inspectorActions);
        box.setPrefWidth(326); box.setMinWidth(300); box.setMaxWidth(350);
        box.getStyleClass().add("workflow-inspector"); return box;
    }

    private VBox consolePane() {
        console.setEditable(false); console.setPrefRowCount(5); console.getStyleClass().add("workflow-console");
        runHistory.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(GraphRun run, boolean empty) {
                super.updateItem(run, empty);
                setText(null);
                if (empty || run == null) { setGraphic(null); return; }
                Label status = new Label(runStatusText(run.status()));
                status.getStyleClass().addAll("jc-badge", runStatusStyle(run.status()));
                Label id = new Label("#" + run.id().substring(0, 8)); id.getStyleClass().add("workflow-run-id");
                Label meta = new Label(run.stepCount() + " 步 · v" + run.workflowVersion());
                meta.getStyleClass().add("workflow-run-meta");
                Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox row = new HBox(8, status, id, spacer, meta); row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        runHistory.getStyleClass().add("workflow-run-list");
        resumeInput.getStyleClass().add("workflow-resume-input");
        resumeInput.setPromptText("人工输入，或恢复副作用节点时填写确认说明");
        Button refresh = styledButton("刷新", "jc-btn-ghost", this::refreshRuns);
        Button resume = styledButton("恢复", "jc-btn-soft", () -> resumeSelectedRun(false));
        Button retry = styledButton("确认并重试", "jc-btn-primary", () -> resumeSelectedRun(true));
        Button cancel = styledButton("取消运行", "jc-btn-danger", this::cancelSelectedRun);
        HBox actions = new HBox(8, resumeInput, resume, retry, cancel, refresh);
        actions.getStyleClass().add("workflow-run-actions");
        HBox.setHgrow(resumeInput, Priority.ALWAYS);
        VBox history = new VBox(6, runHistory, actions);
        VBox.setVgrow(runHistory, Priority.ALWAYS);
        console.getStyleClass().add("workflow-dock-content"); history.getStyleClass().add("workflow-dock-content");
        dockBody.getChildren().addAll(console, history); history.setVisible(false); history.setManaged(false);
        ToggleGroup group = new ToggleGroup();
        ToggleButton trace = dockTab("验证与轨迹", group, true, () -> showDock(console, history));
        ToggleButton runs = dockTab("运行历史与恢复", group, false, () -> showDock(history, console));
        Label dockHint = new Label("节点事件、发布校验与恢复记录"); dockHint.getStyleClass().add("workflow-dock-hint");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox tabs = new HBox(4, trace, runs, spacer, dockHint); tabs.getStyleClass().add("workflow-dock-tabs");
        VBox box = new VBox(tabs, dockBody); box.getStyleClass().add("workflow-dock");
        VBox.setVgrow(dockBody, Priority.ALWAYS); box.setPrefHeight(190); return box;
    }

    private HBox footer() {
        Label hint = new Label("⌘/Ctrl + 滚轮缩放 · 右键节点创建连线 · 右键连线删除");
        hint.getStyleClass().add("workflow-footer-hint");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Button validate = styledButton("校验", "jc-btn-ghost", this::validateCurrent);
        publishButton = styledButton("发布版本", "jc-btn-soft", this::publishCurrent);
        testButton = styledButton("▶ 测试运行", "jc-btn-primary", this::testCurrent);
        Button close = styledButton("关闭", "jc-btn-ghost", stage::close);
        HBox footer = new HBox(8, hint, spacer, validate, publishButton, testButton, close);
        footer.setAlignment(Pos.CENTER_LEFT); footer.getStyleClass().add("modal-foot"); return footer;
    }

    private StackPane canvasViewport() {
        graphPane.setMinSize(1600, 1000);
        ScrollPane viewport = new ScrollPane(graphPane);
        viewport.setPannable(true);
        viewport.setFitToWidth(false);
        viewport.setFitToHeight(false);
        viewport.getStyleClass().add("workflow-viewport");
        viewport.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            if (!event.isControlDown()) return;
            zoom = Math.max(0.5, Math.min(2.0, zoom * (event.getDeltaY() > 0 ? 1.1 : 0.9)));
            graphPane.setScaleX(zoom);
            graphPane.setScaleY(zoom);
            updateZoomLabel();
            event.consume();
        });
        Button zoomOut = styledButton("−", "jc-btn-ghost", () -> setZoom(zoom / 1.15));
        Button zoomIn = styledButton("＋", "jc-btn-ghost", () -> setZoom(zoom * 1.15));
        Button fit = styledButton("适配", "jc-btn-ghost", () -> setZoom(0.8));
        zoomOut.getStyleClass().add("workflow-zoom-btn"); zoomIn.getStyleClass().add("workflow-zoom-btn");
        fit.getStyleClass().add("workflow-fit-btn");
        HBox controls = new HBox(2, zoomOut, zoomLabel, zoomIn, fit);
        controls.setAlignment(Pos.CENTER); controls.getStyleClass().add("workflow-zoom-controls");
        canvasHint.getStyleClass().add("workflow-canvas-hint");
        HBox canvasHead = new HBox(canvasHint, new Region(), controls);
        HBox.setHgrow(canvasHead.getChildren().get(1), Priority.ALWAYS);
        canvasHead.setAlignment(Pos.CENTER_LEFT); canvasHead.getStyleClass().add("workflow-canvas-head");
        StackPane shell = new StackPane(viewport, canvasHead);
        StackPane.setAlignment(canvasHead, Pos.TOP_CENTER);
        shell.getStyleClass().add("workflow-canvas-shell");
        return shell;
    }

    private void createNew() {
        if (!persistPendingDraft()) return;
        GraphDefinition graph = WorkflowEditorModel.blank("新工作流");
        service.definitions().saveDraft(graph); reloadList(graph.id());
    }

    private void cloneSelected() {
        Item item = list.getSelectionModel().getSelectedItem();
        if (item == null) return;
        if (!persistPendingDraft()) return;
        GraphDefinition source = resolveGraph(item);
        var clone = service.definitions().cloneFrom(source, source.name() + " 副本");
        reloadList(clone.id());
    }

    private boolean select(Item item) {
        if (item == null) return true;
        if (!persistPendingDraft()) return false;
        cancelConnection();
        readOnly = item.system;
        nodePalette.setDisable(readOnly);
        if (publishButton != null) publishButton.setDisable(readOnly);
        if (testButton != null) testButton.setDisable(readOnly);
        GraphDefinition selectedGraph = resolveGraph(item);
        editor = new WorkflowEditorModel(selectedGraph);
        selectedNode = null; configEditor.clear();
        configEditor.setDisable(readOnly);
        title.setText(selectedGraph.name());
        titleHint.setText(readOnly ? "系统编排 · 只读，可复制为自定义草稿"
                : item.published ? "已发布版本可在聊天工作流模式中运行" : "草稿仅保存在当前工作区");
        showInspector(null);
        changed(false);
        refreshRuns();
        return true;
    }

    private void changed(boolean scheduleSave) {
        graphPane.render(editor == null ? null : editor.current());
        dirty = scheduleSave && !readOnly;
        dirtyLabel.setText(readOnly ? "只读" : scheduleSave ? "保存中…" : "已保存");
        dirtyLabel.getStyleClass().removeAll("workflow-save-pending", "workflow-save-readonly");
        if (readOnly) dirtyLabel.getStyleClass().add("workflow-save-readonly");
        else if (scheduleSave) dirtyLabel.getStyleClass().add("workflow-save-pending");
        if (scheduleSave && !readOnly) autosave.playFromStart();
    }

    private boolean saveDraft() {
        if (editor == null || readOnly) return true;
        try {
            service.definitions().saveDraft(editor.current()); dirtyLabel.setText("已保存");
            dirtyLabel.getStyleClass().remove("workflow-save-pending");
            dirty = false;
            return true;
        } catch (Exception e) {
            dirtyLabel.setText("保存失败");
            dirtyLabel.getStyleClass().add("workflow-save-pending");
            log("草稿保存失败：" + e.getMessage());
            return false;
        }
    }

    private boolean persistPendingDraft() {
        if (!dirty) return true;
        autosave.stop();
        return saveDraft();
    }

    private GraphDefinition resolveGraph(Item item) {
        if (item.system) return item.graph;
        WorkflowDefinitionRecord record = service.definitions().get(item.id);
        return record == null ? item.graph : record.draft();
    }

    private void validateCurrent() {
        if (editor == null) return;
        List<ValidationIssue> issues = service.definitions().validate(editor.current(), service.nodeRegistry());
        if (issues.isEmpty()) log("校验通过");
        else issues.forEach(i -> log(i.severity() + " [" + i.elementId() + "] " + i.message()));
    }

    private void publishCurrent() {
        if (editor == null || readOnly) return;
        try {
            if (!saveDraft()) return;
            WorkflowDefinitionRecord published =
                    service.definitions().publish(editor.current().id(), service.nodeRegistry());
            log("发布成功");
            try {
                onPublished.accept(published);
            } catch (RuntimeException callbackFailure) {
                log("聊天模式列表刷新失败，可重新展开工作流下拉框重试："
                        + callbackFailure.getMessage());
            }
            reloadList(editor.current().id());
        }
        catch (Exception e) { log("发布失败：" + e.getMessage()); }
    }

    private void testCurrent() {
        if (editor == null) return;
        TextInputDialog dialog = new TextInputDialog("请处理这个输入"); dialog.initOwner(stage);
        dialog.setTitle("测试运行"); dialog.setHeaderText("输入工作流的 input 状态");
        dialog.showAndWait().ifPresent(input -> {
            try {
                service.testRun(editor.current(), input, new ConversationCallbacks() {
                    @Override public void onEvent(ConversationEvent event) { Platform.runLater(() -> log(event.toString())); }
                    @Override public void onComplete() { Platform.runLater(() -> { log("运行结束"); refreshRuns(); }); }
                    @Override public void onError(Throwable error) { Platform.runLater(() -> { log("失败：" + error.getMessage()); refreshRuns(); }); }
                });
            } catch (Exception e) { log("启动失败：" + e.getMessage()); }
        });
    }

    private void applyNodeConfig() {
        if (editor == null || selectedNode == null || readOnly) return;
        try {
            NodeDefinition current = editor.current().nodes().stream()
                    .filter(node -> node.id().equals(selectedNode.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("节点已不存在: " + selectedNode.id()));
            var json = current.type() == NodeType.SYSTEM
                    ? current.config() : MAPPER.readTree(configEditor.getText());
            NodeDefinition n = new NodeDefinition(current.id(), current.type(), current.executorType(),
                    nodeLabel.getText().isBlank() ? current.label() : nodeLabel.getText(),
                    json, current.x(), current.y(),
                    new RetryPolicy(retryAttempts.getValue(), retryBackoff.getValue(),
                            current.retryPolicy().multiplier()),
                    resumeSafety.getValue() == null ? current.resumeSafety() : resumeSafety.getValue());
            editor.updateNode(n); selectedNode = n; changed(true);
        } catch (Exception e) { log("配置 JSON 无效：" + e.getMessage()); }
    }

    private void reloadList(String selectId) {
        List<Item> items = new ArrayList<>();
        service.systemGraphs().list().forEach(g -> items.add(new Item(g.id(), g.name(), g, true, true)));
        for (WorkflowDefinitionRecord r : service.definitions().list(false)) {
            items.add(new Item(r.id(), r.name(), r.draft(), false, r.isPublished()));
        }
        list.getItems().setAll(items);
        if (selectId != null) items.stream().filter(i -> i.id.equals(selectId)).findFirst()
                .ifPresent(i -> list.getSelectionModel().select(i));
        else if (!items.isEmpty()) list.getSelectionModel().selectFirst();
    }

    private void refreshRuns() {
        Item item = list.getSelectionModel().getSelectedItem();
        if (item == null) { runHistory.getItems().clear(); return; }
        try { runHistory.getItems().setAll(service.listRuns(item.id, 100)); }
        catch (Exception e) { log("读取运行历史失败：" + e.getMessage()); }
    }

    private void resumeSelectedRun(boolean confirmed) {
        GraphRun run = runHistory.getSelectionModel().getSelectedItem();
        if (run == null) { log("请先选择运行记录"); return; }
        if (run.status().terminal()) { log("终态运行不可恢复：" + run.status()); return; }
        String input = resumeInput.getText();
        if (run.status() == RunStatus.WAITING_INPUT && input.isBlank()) {
            log("人工中断恢复必须填写输入"); return;
        }
        try {
            service.resumeRun(run.id(), input.isBlank() ? null : input, confirmed, uiCallbacks());
            log("已提交恢复：" + run.id());
        } catch (Exception e) { log("恢复失败：" + e.getMessage()); }
    }

    private void cancelSelectedRun() {
        GraphRun run = runHistory.getSelectionModel().getSelectedItem();
        if (run == null) { log("请先选择运行记录"); return; }
        if (service.cancelRun(run.id())) { log("已取消：" + run.id()); refreshRuns(); }
        else log("运行已结束或不存在");
    }

    private ConversationCallbacks uiCallbacks() {
        return new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) { Platform.runLater(() -> log(event.toString())); }
            @Override public void onComplete() { Platform.runLater(() -> { log("运行结束"); refreshRuns(); }); }
            @Override public void onError(Throwable error) {
                Platform.runLater(() -> { log("失败：" + error.getMessage()); refreshRuns(); });
            }
        };
    }

    private void showInspector(NodeDefinition node) {
        selectedNode = node;
        boolean selected = node != null;
        inspectorActions.setVisible(selected && !readOnly);
        inspectorActions.setManaged(selected && !readOnly);
        if (inspectorBody.getChildren().size() >= 2) {
            javafx.scene.Node empty = inspectorBody.getChildren().get(0);
            javafx.scene.Node form = inspectorBody.getChildren().get(1);
            empty.setVisible(!selected); empty.setManaged(!selected);
            form.setVisible(selected); form.setManaged(selected);
        }
        if (!selected) {
            inspectorTitle.setText("未选择节点"); inspectorType.setText("等待选择");
            nodeLabel.clear(); configEditor.clear();
            return;
        }
        inspectorTitle.setText(node.label()); inspectorType.setText(nodeDisplayName(node.type()));
        nodeLabel.setText(node.label());
        retryAttempts.getValueFactory().setValue(node.retryPolicy().maxAttempts());
        retryBackoff.getValueFactory().setValue((int) node.retryPolicy().initialBackoffMillis());
        resumeSafety.setValue(node.resumeSafety());
        configEditor.setDisable(readOnly || node.type() == NodeType.SYSTEM);
        try { configEditor.setText(MAPPER.writeValueAsString(node.config())); }
        catch (Exception ex) { configEditor.setText(node.config().toString()); }
    }

    private void showDock(javafx.scene.Node shown, javafx.scene.Node hidden) {
        shown.setVisible(true); shown.setManaged(true); hidden.setVisible(false); hidden.setManaged(false);
    }

    private ToggleButton dockTab(String text, ToggleGroup group, boolean selected, Runnable action) {
        ToggleButton button = new ToggleButton(text); button.setToggleGroup(group); button.setSelected(selected);
        button.getStyleClass().add("workflow-dock-tab");
        button.setOnAction(e -> { if (!button.isSelected()) button.setSelected(true); action.run(); });
        return button;
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text); label.getStyleClass().add("workflow-field-label"); return label;
    }

    private void setZoom(double value) {
        zoom = Math.max(0.5, Math.min(2.0, value));
        graphPane.setScaleX(zoom); graphPane.setScaleY(zoom); updateZoomLabel();
    }

    private void updateZoomLabel() { zoomLabel.setText(Math.round(zoom * 100) + "%"); }

    private static String nodeDisplayName(NodeType type) {
        return switch (type) {
            case START -> "开始"; case END -> "结束"; case AGENT -> "智能体"; case TOOL -> "本地工具";
            case CONDITION -> "条件分支"; case TRANSFORM -> "状态转换";
            case HUMAN_INPUT -> "人工输入"; case OUTPUT -> "输出"; case SYSTEM -> "系统阶段";
        };
    }

    private static String nodeGlyph(NodeType type) {
        return switch (type) {
            case START -> "▶"; case END -> "■"; case AGENT -> "✦"; case TOOL -> "⌁";
            case CONDITION -> "◇"; case TRANSFORM -> "⇄"; case HUMAN_INPUT -> "?";
            case OUTPUT -> "↗"; case SYSTEM -> "◆";
        };
    }

    private static String runStatusText(RunStatus status) {
        return switch (status) {
            case CREATED -> "已创建"; case RUNNING -> "运行中"; case WAITING_INPUT -> "待输入";
            case PAUSED -> "已暂停"; case RECOVERY_REQUIRED -> "待恢复"; case COMPLETED -> "已完成";
            case FAILED -> "失败"; case CANCELLED -> "已取消";
        };
    }

    private static String runStatusStyle(RunStatus status) {
        return switch (status) {
            case RUNNING -> "jc-badge-running";
            case WAITING_INPUT, RECOVERY_REQUIRED -> "jc-badge-amber";
            case COMPLETED -> "jc-badge-ok";
            case FAILED -> "jc-badge-failed";
            default -> "jc-badge-stopped";
        };
    }

    private void beginConnection(NodeDefinition source, EdgeKind kind) {
        if (readOnly) { log("系统图为只读，复制为草稿后才能连线"); return; }
        if (source.type() == NodeType.END) { log("END 节点不能创建出口"); return; }
        graphPane.connectSource = source.id();
        graphPane.connectKind = kind;
        canvasHint.setText(kind == EdgeKind.ERROR
                ? "正在创建错误出口：请选择目标节点 · Esc 取消"
                : "正在创建出口：请选择目标节点 · Esc 取消");
        graphPane.updateConnectionStyles();
        log("已选择源节点 [" + source.label() + "]，请点击或右键目标节点");
    }

    private void finishConnection(NodeDefinition target) {
        String source = graphPane.connectSource;
        if (source == null) return;
        if (source.equals(target.id())) { log("不能连接节点自身，请选择其他节点"); return; }
        try {
            connectNodes(source, target.id(), graphPane.connectKind);
            cancelConnection();
            changed(true);
        } catch (Exception ex) {
            log("连线失败：" + ex.getMessage());
        }
    }

    private void cancelConnection() {
        graphPane.connectSource = null;
        graphPane.connectKind = EdgeKind.NORMAL;
        canvasHint.setText("拖拽节点调整流程 · 右键节点创建连线");
        graphPane.updateConnectionStyles();
    }

    private void showNodeContextMenu(NodeDefinition node, VBox card,
                                     javafx.scene.input.ContextMenuEvent event) {
        showInspector(node);
        graphPane.updateSelectedStyle(node.id());
        ContextMenu menu = new ContextMenu();
        if (graphPane.connectSource != null && !graphPane.connectSource.equals(node.id())) {
            MenuItem finish = new MenuItem("连接到此节点");
            finish.setOnAction(e -> finishConnection(node));
            menu.getItems().addAll(finish, new SeparatorMenuItem());
        }
        MenuItem normal = new MenuItem(node.type() == NodeType.CONDITION ? "创建条件出口…" : "创建出口");
        normal.setDisable(readOnly || node.type() == NodeType.END);
        normal.setOnAction(e -> beginConnection(node, EdgeKind.NORMAL));
        MenuItem error = new MenuItem("创建错误出口");
        error.setDisable(readOnly || node.type() == NodeType.END);
        error.setOnAction(e -> beginConnection(node, EdgeKind.ERROR));
        MenuItem inspect = new MenuItem("查看节点配置");
        inspect.setOnAction(e -> showInspector(node));
        menu.getItems().addAll(normal, error, inspect);
        if (graphPane.connectSource != null) {
            MenuItem cancel = new MenuItem("取消当前连线"); cancel.setOnAction(e -> cancelConnection());
            menu.getItems().add(cancel);
        }
        if (!readOnly && node.type() != NodeType.START && node.type() != NodeType.END) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem delete = new MenuItem("删除节点");
            delete.setOnAction(e -> {
                editor.deleteNode(node.id()); showInspector(null); cancelConnection(); changed(true);
            });
            menu.getItems().add(delete);
        }
        menu.show(card, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    private void connectNodes(String sourceId, String targetId, EdgeKind requestedKind) {
        NodeDefinition source = editor.current().nodes().stream()
                .filter(n -> n.id().equals(sourceId)).findFirst().orElseThrow();
        if (requestedKind == EdgeKind.ERROR) {
            editor.connect(sourceId, targetId, EdgeKind.ERROR, null, 0, false);
            return;
        }
        if (source.type() != NodeType.CONDITION) {
            editor.connect(sourceId, targetId);
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage); dialog.setTitle("条件分支");
        TextField path = new TextField(); path.setPromptText("状态路径，例如 result.ok");
        ComboBox<ConditionOperator> operator = new ComboBox<>();
        operator.getItems().setAll(ConditionOperator.values()); operator.setValue(ConditionOperator.EQUAL);
        TextField value = new TextField(); value.setPromptText("JSON 值，例如 true 或 \"done\"");
        CheckBox fallback = new CheckBox("默认出口");
        Spinner<Integer> priority = new Spinner<>(-10_000, 10_000, 0);
        fallback.selectedProperty().addListener((o, old, selected) -> {
            path.setDisable(selected); operator.setDisable(selected); value.setDisable(selected);
        });
        GridPane form = new GridPane(); form.setHgap(8); form.setVgap(8); form.setPadding(new Insets(12));
        form.addRow(0, new Label("状态路径"), path);
        form.addRow(1, new Label("运算符"), operator);
        form.addRow(2, new Label("比较值"), value);
        form.addRow(3, new Label("优先级"), priority);
        form.add(fallback, 1, 4);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        ConditionRule rule = null;
        if (!fallback.isSelected()) {
            if (path.getText().isBlank()) throw new IllegalArgumentException("条件状态路径不能为空");
            com.fasterxml.jackson.databind.JsonNode jsonValue = null;
            if (operator.getValue() != ConditionOperator.EXISTS) {
                try { jsonValue = MAPPER.readTree(value.getText()); }
                catch (Exception ignored) { jsonValue = MAPPER.getNodeFactory().textNode(value.getText()); }
            }
            rule = new ConditionRule(path.getText(), operator.getValue(), jsonValue);
        }
        editor.connect(sourceId, targetId, EdgeKind.CONDITIONAL, rule,
                priority.getValue(), fallback.isSelected());
    }

    private Button styledButton(String text, String variant, Runnable action) {
        Button b = new Button(text); b.getStyleClass().addAll("jc-btn", variant, "jc-btn-sm");
        b.setOnAction(e -> action.run()); return b;
    }

    private void log(String line) { console.appendText(line + "\n"); }
    private static void addCss(Scene scene, String path) {
        var url = WorkflowCenterView.class.getResource(path); if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }

    private record Item(String id, String name, GraphDefinition graph, boolean system, boolean published) {}

    private final class GraphPane extends Pane {
        private final Pane grid = new Pane();
        private final Pane edges = new Pane();
        private final Map<String, VBox> cards = new LinkedHashMap<>();
        private String connectSource;
        private EdgeKind connectKind = EdgeKind.NORMAL;
        GraphPane() {
            getStyleClass().add("workflow-canvas");
            grid.setMouseTransparent(true);
            edges.setPickOnBounds(false);
            getChildren().addAll(grid, edges);
        }

        void render(GraphDefinition graph) {
            cards.clear();
            drawGrid();
            getChildren().setAll(grid, edges);
            if (graph == null) return;
            for (NodeDefinition node : graph.nodes()) {
                Label glyph = new Label(nodeGlyph(node.type())); glyph.getStyleClass().add("workflow-node-glyph");
                Label nodeTitle = new Label(node.label()); nodeTitle.getStyleClass().add("workflow-node-title");
                Label badge = new Label(nodeDisplayName(node.type()));
                badge.getStyleClass().addAll("jc-badge", "workflow-node-badge");
                Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
                HBox head = new HBox(8, glyph, nodeTitle, gap, badge); head.setAlignment(Pos.CENTER_LEFT);
                Label nodeId = new Label(node.id()); nodeId.getStyleClass().add("workflow-node-id");
                VBox card = new VBox(8, head, nodeId);
                card.getStyleClass().addAll("workflow-node", "workflow-node-" + node.type().name().toLowerCase());
                if (selectedNode != null && selectedNode.id().equals(node.id())) {
                    card.getStyleClass().add("workflow-node-selected");
                }
                card.relocate(node.x(), node.y());
                card.setOnMouseClicked(e -> {
                    if (e.getButton() != MouseButton.PRIMARY) return;
                    if (connectSource != null && !connectSource.equals(node.id()) && !readOnly) {
                        finishConnection(node);
                        return;
                    }
                    showInspector(node);
                    updateSelectedStyle(node.id());
                });
                card.setOnContextMenuRequested(e -> showNodeContextMenu(node, card, e));
                final double[] drag = new double[2]; final boolean[] dragging = new boolean[1];
                card.setOnMousePressed(e -> {
                    if (e.getButton() != MouseButton.PRIMARY || readOnly) return;
                    Point2D point = sceneToLocal(e.getSceneX(), e.getSceneY());
                    drag[0] = point.getX() - card.getLayoutX(); drag[1] = point.getY() - card.getLayoutY();
                    dragging[0] = true;
                });
                card.setOnMouseDragged(e -> {
                    if (!dragging[0] || readOnly) return;
                    Point2D point = sceneToLocal(e.getSceneX(), e.getSceneY());
                    card.relocate(Math.max(0, point.getX() - drag[0]), Math.max(52, point.getY() - drag[1]));
                    redraw();
                });
                card.setOnMouseReleased(e -> {
                    if (!dragging[0] || e.getButton() != MouseButton.PRIMARY || readOnly) return;
                    dragging[0] = false;
                    editor.moveNode(node.id(), card.getLayoutX(), card.getLayoutY()); changed(true);
                });
                cards.put(node.id(), card);
                getChildren().add(card);
            }
            redraw();
        }

        void updateSelectedStyle(String selectedId) {
            cards.forEach((id, card) -> {
                card.getStyleClass().remove("workflow-node-selected");
                if (id.equals(selectedId)) card.getStyleClass().add("workflow-node-selected");
            });
        }

        void updateConnectionStyles() {
            cards.forEach((id, card) -> {
                card.getStyleClass().removeAll("workflow-node-connecting", "workflow-node-connect-target");
                if (id.equals(connectSource)) card.getStyleClass().add("workflow-node-connecting");
                else if (connectSource != null) card.getStyleClass().add("workflow-node-connect-target");
            });
        }

        void redraw() {
            edges.getChildren().clear();
            if (editor == null) return;
            for (EdgeDefinition edge : editor.current().edges()) {
                VBox a = cards.get(edge.source());
                VBox b = cards.get(edge.target());
                if (a == null || b == null) continue;
                double x1 = a.getLayoutX() + Math.max(a.getWidth(), 184);
                double y1 = a.getLayoutY() + Math.max(a.getHeight(), 74) / 2;
                double x2 = b.getLayoutX();
                double y2 = b.getLayoutY() + Math.max(b.getHeight(), 74) / 2;
                double bend = Math.max(48, Math.abs(x2 - x1) / 2);
                CubicCurve curve = new CubicCurve(x1, y1, x1 + bend, y1,
                        x2 - bend, y2, x2, y2);
                curve.getStyleClass().addAll("workflow-edge", "workflow-edge-" + edge.kind().name().toLowerCase());
                Tooltip.install(curve, new Tooltip(edge.kind() + (edge.defaultEdge() ? " · 默认" : "")));
                curve.setOnContextMenuRequested(event -> {
                    if (readOnly) return;
                    ContextMenu menu = new ContextMenu();
                    MenuItem remove = new MenuItem("删除连线");
                    remove.setOnAction(e -> { editor.deleteEdge(edge.id()); changed(true); });
                    menu.getItems().add(remove);
                    menu.show(curve, event.getScreenX(), event.getScreenY());
                });
                Polygon arrow = new Polygon(x2, y2, x2 - 8, y2 - 4, x2 - 8, y2 + 4);
                arrow.getStyleClass().addAll("workflow-edge-arrow",
                        "workflow-edge-arrow-" + edge.kind().name().toLowerCase());
                arrow.setMouseTransparent(true);
                edges.getChildren().addAll(curve, arrow);
            }
        }

        private void drawGrid() {
            if (!grid.getChildren().isEmpty()) return;
            for (int x = 0; x <= 1600; x += 32) {
                Line line = new Line(x, 0, x, 1000);
                line.getStyleClass().add(x % 160 == 0 ? "workflow-grid-major" : "workflow-grid-line");
                grid.getChildren().add(line);
            }
            for (int y = 0; y <= 1000; y += 32) {
                Line line = new Line(0, y, 1600, y);
                line.getStyleClass().add(y % 160 == 0 ? "workflow-grid-major" : "workflow-grid-line");
                grid.getChildren().add(line);
            }
        }
    }
}
