package com.javaclaw.chat;

import com.javaclaw.agent.ChatService;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.InteractionHistory;
import com.javaclaw.framework.api.InteractionSurfaceEvent;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.api.InteractionMetrics;
import com.javaclaw.framework.api.TaskOutcome;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Expandable read-only view of the durable Thread → Turn → Step hierarchy. */
final class ChatExecutionInspector {
    private static final String SHORTCUT_HELP = "Cmd/Ctrl+1/2/3 切页；Cmd/Ctrl+Shift+E 导出指标。";
    private static final java.util.regex.Pattern UUID = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ChatExecutionInspector() { }

    static void show(Window owner, ChatService service, String sessionId) {
        TreeItem<String> thread = new TreeItem<>("会话 " + sessionId);
        thread.setExpanded(true);
        for (var turn : service.sessionTurns(sessionId)) {
            TreeItem<String> item = new TreeItem<>("轮次 " + turn.id() + " · " + turn.state());
            thread.getChildren().add(item);
            item.getChildren().add(new TreeItem<>("时间：" + turn.createdAt() + " → " + turn.updatedAt()));
            if (turn.output() != null) item.getChildren().add(new TreeItem<>("轮次结果：\n" + value(turn.output())));
            var steps = service.sessionSteps(sessionId, turn.id());
            if (steps.isEmpty()) item.getChildren().add(new TreeItem<>("此轮没有可用的原子步骤记录"));
            for (AgentStep step : steps) {
                TreeItem<String> atomic = new TreeItem<>(step.kind() + " · " + step.state()
                        + " · " + step.id());
                atomic.getChildren().add(new TreeItem<>("输入：" + value(step.input())));
                atomic.getChildren().add(new TreeItem<>("输出：" + value(step.output())));
                atomic.getChildren().add(new TreeItem<>("用量：" + value(step.usage())));
                if (step.causationStepId() != null) atomic.getChildren().add(new TreeItem<>("来源步骤：" + step.causationStepId()));
                if (step.error() != null) atomic.getChildren().add(new TreeItem<>("错误：" + step.error()));
                item.getChildren().add(atomic);
            }
        }
        TreeView<String> tree = recordTree(thread, "会话轮次与步骤");
        TextArea details = recordDetails("选择左侧记录，查看完整输入、输出与来源");
        tree.getSelectionModel().selectedItemProperty().addListener((observable, before, selected) ->
                details.setText(selected == null ? "" : selected.getValue()));
        var content = recordSplit(tree, details, "执行轨迹", "按轮次展开步骤与回执", "记录详情", "完整内容可选择、复制");
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("会话执行记录");
        if (owner != null) dialog.initOwner(owner);
        var tabs = new TabPane();
        tabs.getStyleClass().add("execution-tabs");
        Tab executions = new Tab("轮次与步骤", content);
        executions.setClosable(false);
        Tab interactions = new Tab("浏览器与桌面交互");
        interactions.setClosable(false);
        Tab metrics = new Tab("用量与结果");
        metrics.setClosable(false);
        tabs.getTabs().addAll(executions, interactions, metrics);
        tabs.setAccessibleHelp(SHORTCUT_HELP);
        var body = new VBox(18, header("会话执行记录", "会话 " + sessionId), tabs,
                label(SHORTCUT_HELP, "execution-shortcuts"));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        body.setMinSize(0, 0);
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().setAccessibleHelp(SHORTCUT_HELP);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.setResizable(true);
        styleInspector(dialog, owner);
        interactions.setContent(interactionView(dialog, service, sessionId));
        Button export = new Button("导出指标 JSON");
        export.setAccessibleHelp("Cmd/Ctrl+Shift+E 导出已读取的指标 JSON。");
        metrics.setContent(metricsView(dialog, service, sessionId, export));
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!event.isShortcutDown() || event.isAltDown()) return;
            if (event.isShiftDown() && event.getCode() == KeyCode.E) {
                tabs.getSelectionModel().select(metrics);
                if (!export.isDisabled()) export.fire();
                event.consume();
            } else if (!event.isShiftDown()) {
                int index = switch (event.getCode()) {
                    case DIGIT1, NUMPAD1 -> 0;
                    case DIGIT2, NUMPAD2 -> 1;
                    case DIGIT3, NUMPAD3 -> 2;
                    default -> -1;
                };
                if (index >= 0) {
                    tabs.getSelectionModel().select(index);
                    event.consume();
                }
            }
        });
        dialog.showAndWait();
    }

    private static javafx.scene.Node interactionView(Dialog<?> dialog, ChatService service, String sessionId) {
        TreeItem<String> root = new TreeItem<>("正在读取交互历史…");
        root.setExpanded(true);
        TreeView<String> tree = recordTree(root, "浏览器与桌面交互历史");
        TextArea details = recordDetails("选择事件，查看身份、关系、回执状态与证据引用");
        java.util.Map<TreeItem<String>, InteractionHistory.Entry> sources = new java.util.IdentityHashMap<>();
        Button source = action("查看原始步骤", "jc-btn-soft");
        source.setDisable(true);
        tree.getSelectionModel().selectedItemProperty().addListener((observable, before, selected) -> {
            InteractionHistory.Entry entry = sources.get(selected);
            source.setDisable(entry == null);
            details.setText(entry == null ? selected == null ? "" : selected.getValue() : describe(entry));
        });
        source.setOnAction(ignored -> {
            var entry = sources.get(tree.getSelectionModel().getSelectedItem());
            if (entry != null) showSource(dialog.getDialogPane().getScene().getWindow(), service, sessionId, entry.ownerRunId());
        });
        Button refresh = action("刷新历史", "jc-btn-ghost");
        var split = recordSplit(tree, details, "交互对象与事件", "浏览器按会话与页面，桌面按应用与窗口分组",
                "事件详情", "身份、关联关系与原始证据");
        BorderPane panel = new BorderPane(split);
        var note = notice("历史记录不代表当前画面。“观察期间”不表示动作导致新窗口，未知的桌面父窗关系仍保留为未知。");
        var controls = new VBox(12, toolbar(label("交互历史", "execution-section-title"), refresh, source), note);
        controls.getStyleClass().add("execution-toolbar-area");
        panel.setTop(controls);
        java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
        Runnable load = () -> {
            long requested = revision.incrementAndGet();
            refresh.setDisable(true);
            Thread.ofVirtual().name("javaclaw-interaction-inspector").start(() -> {
                try {
                    InteractionHistory history = service.sessionInteractionHistory(sessionId, 500);
                    Platform.runLater(() -> {
                        if (!dialog.isShowing() || requested != revision.get()) return;
                        sources.clear();
                        root.getChildren().clear();
                        root.setValue("交互历史 · " + history.entries().size() + " 条"
                                + (history.truncated() ? "（仅显示最近 500 条）" : ""));
                        populateHistory(root, sources, history);
                        if (history.entries().isEmpty()) root.getChildren().add(new TreeItem<>("暂无浏览器或桌面交互记录"));
                        refresh.setDisable(false);
                    });
                } catch (RuntimeException unavailable) {
                    Platform.runLater(() -> {
                        if (!dialog.isShowing() || requested != revision.get()) return;
                        root.setValue("交互历史暂不可读（会话可能已关闭或删除）");
                        refresh.setDisable(false);
                    });
                }
            });
        };
        refresh.setOnAction(ignored -> load.run());
        dialog.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_SHOWN, ignored -> load.run());
        return panel;
    }

    private static javafx.scene.Node metricsView(Dialog<?> dialog, ChatService service, String sessionId,
            Button export) {
        javafx.scene.control.ComboBox<RunId> turns = new javafx.scene.control.ComboBox<>();
        java.util.Map<RunId, String> turnLabels = new java.util.LinkedHashMap<>();
        var snapshots = service.sessionTurns(sessionId);
        for (int i = 0; i < snapshots.size(); i++) {
            var turn = snapshots.get(i);
            turns.getItems().add(turn.id());
            turnLabels.put(turn.id(), "轮次 " + (i + 1) + " · " + time(turn.createdAt()) + " · " + state(turn.state()));
        }
        turns.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(RunId id) { return id == null ? "" : turnLabels.getOrDefault(id, id.value()); }
            @Override public RunId fromString(String text) { throw new UnsupportedOperationException("只读轮次选择器"); }
        });
        turns.getStyleClass().addAll("settings-combo", "execution-turn-picker");
        turns.setAccessibleText("选择查看指标的轮次");
        turns.setPrefWidth(350);
        turns.setMinWidth(180);
        turns.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(turns, Priority.ALWAYS);
        if (!turns.getItems().isEmpty()) turns.getSelectionModel().selectLast();
        TextArea raw = recordDetails("读取指标后可查看原始 JSON");
        raw.setWrapText(false);
        raw.setPrefHeight(300);
        TitledPane advanced = new TitledPane("原始指标 JSON", raw);
        advanced.setExpanded(false);
        advanced.setAnimated(false);
        advanced.getStyleClass().add("execution-advanced");
        VBox overview = new VBox(14);
        overview.getStyleClass().add("execution-overview");
        overview.getChildren().add(notice(turns.getItems().isEmpty() ? "此会话暂无执行轮次。" : "正在读取此轮次的用量与验收结果…"));
        VBox metricsBody = new VBox(14, overview, advanced);
        ScrollPane scroll = new ScrollPane(metricsBody);
        scroll.setFitToWidth(true);
        scroll.setMinSize(0, 0);
        scroll.getStyleClass().add("execution-metrics-scroll");
        Button refresh = action("刷新指标", "jc-btn-ghost");
        export.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        refresh.setDisable(turns.getItems().isEmpty());
        export.setDisable(true);
        var note = notice("只读快照，不发起模型调用。Token 与金额仅合计已记录部分，缺失用量和报价单独标注。");
        note.setAccessibleHelp(SHORTCUT_HELP);
        Label turnLabel = label("查看轮次", "execution-field-label");
        turnLabel.setLabelFor(turns);
        var row = new HBox(10, turnLabel, turns, refresh, export);
        row.setAlignment(Pos.CENTER_LEFT);
        var controls = new VBox(12, row, note);
        controls.getStyleClass().add("execution-toolbar-area");
        BorderPane panel = new BorderPane(scroll);
        panel.setTop(controls);
        java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicReference<String> exported = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable load = () -> {
            RunId selected = turns.getValue();
            if (selected == null) return;
            long requested = revision.incrementAndGet();
            refresh.setDisable(true);
            export.setDisable(true);
            exported.set(null);
            raw.clear();
            overview.getChildren().setAll(notice("正在读取此轮次的用量与验收结果…"));
            Thread.ofVirtual().name("javaclaw-interaction-metrics").start(() -> {
                try {
                    var metrics = service.sessionInteractionMetrics(sessionId, selected);
                    String serialized = metricsJson(metrics).toPrettyString();
                    Platform.runLater(() -> {
                        if (!dialog.isShowing() || requested != revision.get()) return;
                        overview.getChildren().setAll(metricsOverview(metrics));
                        raw.setText(serialized);
                        exported.set(serialized);
                        refresh.setDisable(false);
                        export.setDisable(false);
                    });
                } catch (RuntimeException unavailable) {
                    Platform.runLater(() -> {
                        if (!dialog.isShowing() || requested != revision.get()) return;
                        overview.getChildren().setAll(notice("指标暂不可读或来源不属于当前会话，可刷新重试。"));
                        refresh.setDisable(false);
                    });
                }
            });
        };
        turns.valueProperty().addListener((observable, before, selected) -> load.run());
        refresh.setOnAction(ignored -> load.run());
        export.setOnAction(ignored -> {
            String snapshot = exported.get();
            RunId snapshotRun = turns.getValue();
            if (snapshot == null || snapshotRun == null) return;
            var chooser = new javafx.stage.FileChooser();
            chooser.setTitle("导出交互用量指标");
            chooser.setInitialFileName("interaction-metrics-" + snapshotRun + ".json");
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
            var file = chooser.showSaveDialog(dialog.getDialogPane().getScene().getWindow());
            if (file == null) return;
            try { java.nio.file.Files.writeString(file.toPath(), snapshot, java.nio.charset.StandardCharsets.UTF_8); }
            catch (java.io.IOException failure) {
                var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR, "无法写入所选指标文件。", ButtonType.OK);
                alert.initOwner(dialog.getDialogPane().getScene().getWindow());
                attachStyles(alert, dialog.getDialogPane().getScene().getWindow());
                alert.showAndWait();
            }
        });
        dialog.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_SHOWN, ignored -> load.run());
        return panel;
    }

    private static Node metricsOverview(InteractionMetrics metrics) {
        var totals = metrics.aggregate();
        Label result = label(metrics.taskResult() == null ? "尚无验收结果" : outcome(metrics.taskResult().outcome()), "execution-result");
        if (metrics.taskResult() != null) result.setTooltip(new Tooltip(metrics.taskResult().outcome().name()));
        Label runState = label("运行状态 · " + state(metrics.state()), "jc-badge", "jc-badge-stopped");
        runState.setTooltip(new Tooltip(metrics.state().name()));
        var resultCard = new VBox(8, toolbar(label("验收结果", "execution-section-title"), runState), result,
                label("已满足 " + metrics.satisfiedCriterionCount() + " 项    未满足 " + metrics.unmetCriterionCount()
                        + " 项    证据引用 " + metrics.evidenceReferenceCount() + " 条", "execution-secondary"),
                label("轮次 " + metrics.parentRunId(), "execution-secondary"));
        resultCard.getStyleClass().addAll("jc-card", "execution-summary-card");

        var stats = new HBox(12,
                stat("已记录输入 Token", number(totals.inputTokens()), "用量未知 " + totals.unknownUsageAttempts() + " 次"),
                stat("已记录输出 Token", number(totals.outputTokens()), "主轮、子轮及关联维护合计"),
                stat("模型服务尝试", number(totals.providerAttempts()), "重试 " + totals.retryAttempts() + " 次 · 媒体输入 " + totals.mediaInputs()),
                stat("业务耗时", duration(metrics.elapsedMillis()), metrics.terminalFamily() ? "执行链轮次已全部终结" : "执行链仍有未终结轮次"));
        stats.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        VBox activity = summaryCard("模型与交互",
                metricRow("执行链 / 关联维护尝试", metrics.executionFamily().providerAttempts() + " / " + metrics.associatedMaintenance().providerAttempts()),
                metricRow("主轮 / 子轮尝试", metrics.direct().providerAttempts() + " / " + metrics.descendants().providerAttempts()),
                metricRow("关联维护轮次", number(metrics.associatedMaintenanceRuns().size())),
                metricRow("委派 / 模式切换", metrics.delegationCalls() + " / " + metrics.modeSwitches()),
                metricRow("澄清 / 审批事件", metrics.clarificationRequests() + " / " + metrics.approvalRequests()));
        VBox completeness = summaryCard("费用与记录完整性",
                metricRow("已知估算金额 · CNY", totals.estimatedCostCny().stripTrailingZeros().toPlainString()),
                metricRow("用量未知尝试", number(totals.unknownUsageAttempts())),
                metricRow("报价未知尝试", number(totals.unknownCostAttempts())),
                metricRow("提示长度未知尝试", number(totals.unknownPromptAttempts())),
                metricRow("迟到用量尝试", number(totals.lateUsageAttempts())));
        HBox breakdown = new HBox(12, activity, completeness);
        HBox.setHgrow(activity, Priority.ALWAYS);
        HBox.setHgrow(completeness, Priority.ALWAYS);
        Label observed = label("读取于 " + time(metrics.observedAt())
                + " · 之后仍可能新增维护或迟到用量" + (metrics.provisional() ? " · 暂定快照" : ""), "execution-secondary");
        return new VBox(14, resultCard, stats, breakdown, observed);
    }

    private static VBox stat(String title, String value, String detail) {
        VBox card = new VBox(8, label(title, "jc-stat-label"), label(value, "execution-stat-value"),
                label(detail, "execution-secondary"));
        card.getStyleClass().addAll("jc-card", "execution-stat-card");
        card.setMinWidth(0);
        card.setPrefWidth(180);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static VBox summaryCard(String title, Node... rows) {
        VBox card = new VBox(10, label(title, "execution-section-title"));
        card.getChildren().addAll(rows);
        card.getStyleClass().addAll("jc-card", "execution-summary-card");
        card.setMinWidth(0);
        card.setPrefWidth(400);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static HBox metricRow(String title, String value) {
        Label key = label(title, "execution-secondary");
        Label amount = label(value, "execution-metric-value");
        amount.setMinWidth(Region.USE_PREF_SIZE);
        return toolbar(key, amount);
    }

    private static VBox header(String title, String identity) {
        Label id = label(identity, "execution-secondary");
        id.setTooltip(new Tooltip(identity));
        return new VBox(6, toolbar(label(title, "sec-title"), label("只读记录", "jc-badge", "jc-badge-soft")), id);
    }

    private static HBox toolbar(Node first, Node... actions) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, first, spacer);
        row.getChildren().addAll(actions);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Button action(String text, String variant) {
        Button button = new Button(text);
        button.getStyleClass().addAll("jc-btn", variant, "jc-btn-sm");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static Label label(String text, String... styles) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styles);
        label.setWrapText(true);
        label.setMinWidth(0);
        return label;
    }

    private static Label notice(String text) {
        Label note = label(text, "execution-notice");
        note.setMaxWidth(Double.MAX_VALUE);
        return note;
    }

    private static TextArea recordDetails(String prompt) {
        TextArea details = new TextArea();
        details.setEditable(false);
        details.setWrapText(true);
        details.setPromptText(prompt);
        details.setAccessibleHelp(prompt);
        details.getStyleClass().add("execution-record-text");
        details.setMinSize(0, 0);
        return details;
    }

    private static TreeView<String> recordTree(TreeItem<String> root, String name) {
        TreeView<String> tree = new TreeView<>(root);
        tree.setAccessibleText(name);
        tree.getStyleClass().add("execution-tree");
        tree.setMinSize(0, 0);
        tree.setCellFactory(ignored -> new javafx.scene.control.TreeCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(null);
                setText(empty || item == null ? null : compactLabel(item));
            }
        });
        return tree;
    }

    /** Compact only the displayed row; the selection and detail pane retain the complete source. */
    private static String compactLabel(String source) {
        int newline = source.indexOf('\n');
        String first = (newline < 0 ? source : source.substring(0, newline)).strip();
        String compact = UUID.matcher(first).replaceAll(match -> match.group().substring(0, 8) + "…");
        if (compact.length() > 120) compact = compact.substring(0, 120) + "…";
        return compact + (newline >= 0 ? " …" : "");
    }

    private static SplitPane recordSplit(TreeView<String> tree, TextArea details,
            String treeTitle, String treeHint, String detailsTitle, String detailsHint) {
        VBox left = recordPanel(treeTitle, treeHint, tree);
        VBox right = recordPanel(detailsTitle, detailsHint, details);
        left.setMinWidth(220);
        right.setMinWidth(260);
        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.41);
        split.setMinSize(0, 0);
        split.getStyleClass().add("execution-split");
        return split;
    }

    private static VBox recordPanel(String title, String hint, Node content) {
        VBox heading = new VBox(4, label(title, "execution-section-title"), label(hint, "execution-secondary"));
        heading.getStyleClass().add("execution-panel-heading");
        VBox card = new VBox(heading, content);
        card.getStyleClass().addAll("jc-card", "execution-record-panel");
        card.setMinHeight(0);
        VBox.setVgrow(content, Priority.ALWAYS);
        return card;
    }

    private static void attachStyles(Dialog<?> dialog, Window owner) {
        var pane = dialog.getDialogPane();
        // Scene-level styles allow FontManager's later user stylesheet to override the default font.
        java.util.function.Consumer<javafx.scene.Scene> loadStyles = scene -> {
            for (String path : new String[] {"/css/chat.css", "/css/execution-inspector.css"}) {
                var css = ChatExecutionInspector.class.getResource(path);
                if (css != null && !scene.getStylesheets().contains(css.toExternalForm()))
                    scene.getStylesheets().add(css.toExternalForm());
            }
        };
        pane.sceneProperty().addListener((observable, before, scene) -> { if (scene != null) loadStyles.accept(scene); });
        if (pane.getScene() != null) loadStyles.accept(pane.getScene());
        for (String style : new String[] {"root", "jc-dialog-pane", "execution-inspector"})
            if (!pane.getStyleClass().contains(style)) pane.getStyleClass().add(style);
        if (owner != null && owner.getScene() != null) {
            owner.getScene().getRoot().getStyleClass().stream().filter(style -> style.startsWith("theme-"))
                    .forEach(style -> { if (!pane.getStyleClass().contains(style)) pane.getStyleClass().add(style); });
        }
    }

    /** This resizable inspector uses tool-window bounds, without changing ordinary form dialogs. */
    private static void styleInspector(Dialog<?> dialog, Window owner) {
        attachStyles(dialog, owner);
        var screen = owner == null ? Screen.getPrimary() : Screen.getScreensForRectangle(
                owner.getX(), owner.getY(), owner.getWidth(), owner.getHeight()).stream().findFirst().orElse(Screen.getPrimary());
        var bounds = screen.getVisualBounds();
        double width = Math.max(1, bounds.getWidth() - 48);
        double height = Math.max(1, bounds.getHeight() - 64);
        dialog.getDialogPane().setPrefSize(Math.min(1080, width), Math.min(740, height));
        dialog.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_SHOWN, ignored -> Platform.runLater(() -> {
            var scene = dialog.getDialogPane().getScene();
            if (!dialog.isShowing() || scene == null) return;
            if (scene.getWindow() instanceof Stage stage) {
                stage.setMinWidth(Math.min(780, width));
                stage.setMinHeight(Math.min(540, height));
                stage.setWidth(Math.min(1080, width));
                stage.setHeight(Math.min(768, height));
                stage.setX(Math.max(bounds.getMinX(), Math.min(stage.getX(), bounds.getMaxX() - stage.getWidth())));
                stage.setY(Math.max(bounds.getMinY(), Math.min(stage.getY(), bounds.getMaxY() - stage.getHeight())));
            }
        }));
    }

    private static String time(java.time.Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault()).format(instant);
    }

    private static String number(long value) { return String.format(java.util.Locale.ROOT, "%,d", value); }

    private static String duration(long millis) {
        if (millis < 1000) return millis + " ms";
        if (millis < 60_000) return String.format(java.util.Locale.ROOT, "%.1f 秒", millis / 1000.0);
        return millis / 60_000 + " 分 " + millis % 60_000 / 1000 + " 秒";
    }

    private static String state(RunState state) {
        return switch (state) {
            case CREATED -> "已创建";
            case RUNNING -> "运行中";
            case WAITING_INPUT -> "等待输入";
            case WAITING_APPROVAL -> "等待审批";
            case WAITING_CHILD -> "等待子任务";
            case WAITING_EVENT -> "等待事件";
            case PAUSED -> "已暂停";
            case RECOVERY_BLOCKED_MISSING_EXTENSION -> "恢复受阻";
            case COMPLETED -> "已完成";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
        };
    }

    private static String outcome(TaskOutcome outcome) {
        return switch (outcome) {
            case VERIFIED_COMPLETE -> "验收通过";
            case DELIVERED -> "已交付";
            case PARTIAL -> "部分完成";
            case BLOCKED -> "任务受阻";
            case UNVERIFIED -> "尚未验证";
            case NOT_APPLICABLE -> "无需验收";
        };
    }

    /** Numeric metadata only: no prompts, private tool output, criterion text or model answer. */
    private static com.fasterxml.jackson.databind.node.ObjectNode metricsJson(com.javaclaw.framework.api.InteractionMetrics metrics) {
        var value = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        value.put("schemaVersion", 2);
        value.put("parentRunId", metrics.parentRunId().value());
        value.put("observedAt", metrics.observedAt().toString());
        value.put("state", metrics.state().name());
        value.put("elapsedMillis", metrics.elapsedMillis());
        value.put("terminalFamily", metrics.terminalFamily());
        value.set("direct", totalsJson(metrics.direct()));
        value.set("descendants", totalsJson(metrics.descendants()));
        value.set("aggregate", totalsJson(metrics.aggregate()));
        value.set("executionFamily", totalsJson(metrics.executionFamily()));
        value.set("associatedMaintenance", totalsJson(metrics.associatedMaintenance()));
        value.put("provisional", metrics.provisional());
        value.put("accountingScope", "readable execution family plus host-attested maintenance sidecars available at read time");
        var associations = value.putArray("associatedMaintenanceRuns");
        java.util.Map<RunId, com.javaclaw.framework.api.InteractionMetrics.MaintenanceAssociation> maintenance = new java.util.HashMap<>();
        for (var association : metrics.associatedMaintenanceRuns()) {
            maintenance.put(association.runId(), association);
            associations.addObject().put("runId", association.runId().value())
                    .put("originRunId", association.originRunId().value())
                    .put("maintenanceRootRunId", association.maintenanceRootRunId().value());
        }
        var purposes = value.putObject("byPurpose");
        metrics.byPurpose().forEach((purpose, totals) -> purposes.set(purpose, totalsJson(totals)));
        value.put("delegationCalls", metrics.delegationCalls());
        value.put("interactionChildren", metrics.interactionChildren());
        value.put("modeSelections", metrics.modeSelections());
        value.put("modeSwitches", metrics.modeSwitches());
        value.put("clarificationRequests", metrics.clarificationRequests());
        value.put("approvalRequests", metrics.approvalRequests());
        value.put("taskOutcome", metrics.taskResult() == null ? "UNKNOWN" : metrics.taskResult().outcome().name());
        value.put("satisfiedCriterionCount", metrics.satisfiedCriterionCount());
        value.put("unmetCriterionCount", metrics.unmetCriterionCount());
        value.put("evidenceReferenceCount", metrics.evidenceReferenceCount());
        var runs = value.putArray("runs");
        for (var run : metrics.runs()) {
            var entry = runs.addObject();
            entry.put("runId", run.runId().value());
            if (run.parentRunId() == null) entry.putNull("parentRunId"); else entry.put("parentRunId", run.parentRunId().value());
            var association = maintenance.get(run.runId());
            entry.put("attribution", association == null ? "EXECUTION_FAMILY" : "ASSOCIATED_MAINTENANCE");
            if (association != null) {
                entry.put("originRunId", association.originRunId().value());
                entry.put("maintenanceRootRunId", association.maintenanceRootRunId().value());
            }
            entry.put("sourceKind", run.sourceKind());
            entry.put("state", run.state().name());
            entry.put("lastSequence", run.lastSequence());
            entry.put("elapsedMillis", run.elapsedMillis());
            entry.set("totals", totalsJson(run.totals()));
        }
        return value;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode totalsJson(com.javaclaw.framework.api.InteractionMetrics.Totals totals) {
        var value = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        value.put("providerAttempts", totals.providerAttempts());
        value.put("primaryAttempts", totals.primaryAttempts());
        value.put("modelTaskAttempts", totals.modelTaskAttempts());
        value.put("completedAttempts", totals.completedAttempts());
        value.put("failedAttempts", totals.failedAttempts());
        value.put("unfinishedAttempts", totals.unfinishedAttempts());
        value.put("retryAttempts", totals.retryAttempts());
        value.put("cacheHits", totals.cacheHits());
        value.put("inputTokensRecorded", totals.inputTokens());
        value.put("outputTokensRecorded", totals.outputTokens());
        value.put("estimatedCostCnyRecorded", totals.estimatedCostCny());
        value.put("unknownUsageAttempts", totals.unknownUsageAttempts());
        value.put("lateUsageAttempts", totals.lateUsageAttempts());
        value.put("unknownCostAttempts", totals.unknownCostAttempts());
        value.put("journaledPromptCharacters", totals.journaledPromptCharacters());
        value.put("unknownPromptAttempts", totals.unknownPromptAttempts());
        value.put("toolSchemaCharacters", totals.toolSchemaCharacters());
        value.put("mediaInputs", totals.mediaInputs());
        return value;
    }

    private static void populateHistory(TreeItem<String> root,
            java.util.Map<TreeItem<String>, InteractionHistory.Entry> sources, InteractionHistory history) {
        java.util.Map<String, TreeItem<String>> groups = new java.util.LinkedHashMap<>();
        java.util.Map<String, HistoryWindow> windows = new java.util.LinkedHashMap<>();
        java.util.Map<String, InteractionHistory.Entry> nativeParents = new java.util.LinkedHashMap<>();
        java.util.List<InteractionHistory.Entry> afterCandidates = new java.util.ArrayList<>();
        for (var entry : history.entries()) {
            String mode = entry.mode() == InteractionSurfaceEvent.Mode.BROWSER ? "浏览器" : "桌面";
            TreeItem<String> modeRoot = group(root, groups, mode, mode);
            TreeItem<String> parent;
            if (entry.surface() != null) {
                var window = historySurfaceGroup(modeRoot, groups, entry.surface(), false);
                parent = window.item();
                rememberWindow(windows, window, entry.surface());
                if (entry.surface().relation() == InteractionSurfaceEvent.Relation.PARENT
                        && entry.surface().relationProof() == InteractionSurfaceEvent.RelationProof.HOST_PROVEN)
                    nativeParents.put(window.key(), entry);
                if (entry.surface().causeProof() == InteractionSurfaceEvent.CauseProof.OBSERVED_AFTER)
                    afterCandidates.add(entry);
            } else {
                TreeItem<String> actions = group(modeRoot, groups, mode + "/actions", "工具动作与回执");
                TreeItem<String> turn = group(actions, groups, mode + "/run/" + entry.ownerRunId(), "轮次 " + entry.ownerRunId());
                parent = group(turn, groups, mode + "/run/" + entry.ownerRunId() + "/" + entry.invocationId(),
                        entry.tool() + " · " + entry.invocationId());
            }
            TreeItem<String> item = new TreeItem<>(entry.observedAt() + " · "
                    + (entry.surface() == null ? entry.kind() : surfaceKind(entry.surface().kind())) + " · " + entry.status());
            sources.put(item, entry);
            parent.getChildren().add(item);
            if (entry.surface() == null) {
                for (var surface : entry.associatedSurfaces()) {
                    HistorySurfaceGroup window = historySurfaceGroup(modeRoot, groups, surface, true);
                    rememberWindow(windows, window, surface);
                    TreeItem<String> related = group(window.item(), groups, window.key() + "/actions", "观察关联的动作（不表示因果）");
                    TreeItem<String> action = new TreeItem<>(entry.observedAt() + " · " + entry.tool() + " · " + entry.status());
                    related.getChildren().add(action);
                    sources.put(action, entry);
                }
            }
        }
        installNativeParentTree(groups, windows, nativeParents);
        appendAfterCandidates(groups, sources, windows, afterCandidates);
    }

    private record HistorySurfaceGroup(TreeItem<String> item, String key) { }
    private record HistoryWindow(HistorySurfaceGroup group, InteractionSurfaceEvent surface) { }

    private static void rememberWindow(java.util.Map<String, HistoryWindow> windows, HistorySurfaceGroup group,
            InteractionSurfaceEvent surface) {
        if (surface.mode() == InteractionSurfaceEvent.Mode.DESKTOP && !surface.surfaceId().isBlank())
            windows.put(group.key(), new HistoryWindow(group, surface));
    }

    /** Tree edges come exclusively from native PARENT proof, never PID or observation order. */
    private static void installNativeParentTree(java.util.Map<String, TreeItem<String>> groups,
            java.util.Map<String, HistoryWindow> windows,
            java.util.Map<String, InteractionHistory.Entry> nativeParents) {
        for (var fact : nativeParents.entrySet()) {
            var child = windows.get(fact.getKey());
            if (child == null || !nativeProcessRuntime(child.surface().runtimeId())) continue;
            var proof = fact.getValue().surface();
            String processKey = fact.getKey().substring(0, fact.getKey().lastIndexOf("/surface/"));
            String parentKey = processKey + "/surface/" + proof.relatedSurfaceId();
            var knownParent = windows.get(parentKey);
            // The parent ID alone does not establish its application or process. Only
            // observed parents in the exact same process can nest under this process tree.
            if (knownParent == null) {
                group(child.group().item(), groups, fact.getKey() + "/parent/" + proof.relatedSurfaceId(),
                        "原生已证明的父窗口：" + proof.relatedSurfaceId() + "（所属进程未在此树核实）");
                continue;
            }
            TreeItem<String> parent = knownParent.group().item();
            boolean cycle = false;
            for (TreeItem<String> ancestor = parent; ancestor != null; ancestor = ancestor.getParent()) {
                if (ancestor == child.group().item()) { cycle = true; break; }
            }
            if (cycle) {
                group(child.group().item(), groups, fact.getKey() + "/parent-conflict",
                        "历史父窗口关系存在环，保留原始证据且不嵌套");
                continue;
            }
            var item = child.group().item();
            if (item.getParent() != null) item.getParent().getChildren().remove(item);
            parent.getChildren().add(item);
            item.setValue("实际窗口 " + proof.surfaceId() + " · 原生父子关系已证明（历史）");
        }
    }

    /** All candidates remain visible. Observation association does not select or create a window. */
    private static void appendAfterCandidates(java.util.Map<String, TreeItem<String>> groups,
            java.util.Map<TreeItem<String>, InteractionHistory.Entry> sources,
            java.util.Map<String, HistoryWindow> windows, java.util.List<InteractionHistory.Entry> candidates) {
        java.util.Map<String, java.util.List<HistoryWindow>> bySurface = new java.util.HashMap<>();
        for (var window : windows.values())
            bySurface.computeIfAbsent(window.surface().surfaceId(), ignored -> new java.util.ArrayList<>()).add(window);
        for (var entry : candidates) {
            var surface = entry.surface();
            var origins = bySurface.getOrDefault(surface.sourceSurfaceId(), java.util.List.of());
            var observed = bySurface.getOrDefault(surface.surfaceId(), java.util.List.of());
            HistoryWindow anchor = origins.size() == 1 ? origins.getFirst()
                    : observed.stream().filter(window -> window.surface().runtimeId().equals(surface.runtimeId())
                            && window.surface().applicationId().equals(surface.applicationId())).findFirst().orElse(null);
            if (anchor == null) continue;
            String key = anchor.group().key() + "/after/" + entry.ownerRunId() + "/" + surface.causedByInvocationId();
            TreeItem<String> source = group(anchor.group().item(), groups, key,
                    "操作后发现的候选窗口（观察关联，创建因果未知） · " + surface.causedByInvocationId());
            if (origins.size() != 1)
                group(source, groups, key + "/source-missing", "来源窗口未收录或不唯一：" + surface.sourceSurfaceId());
            TreeItem<String> candidate = new TreeItem<>(surface.observedAt() + " · 实际窗口 "
                    + surface.surfaceId() + " · OBSERVED_AFTER · 创建因果未知");
            source.getChildren().add(candidate);
            sources.put(candidate, entry);
        }
    }

    private static String surfaceKind(InteractionSurfaceEvent.Kind kind) {
        return switch (kind) {
            case WINDOW_DISCOVERED -> "发现窗口";
            case WINDOW_OPENED -> "窗口会话已打开（不表示新建）";
            case WINDOW_HIDDEN -> "窗口已隐藏";
            case WINDOW_SHOWN -> "窗口重新显示";
            case WINDOW_UNAVAILABLE -> "窗口不可用（未证明关闭）";
            case WINDOW_CLOSED -> "窗口已关闭（原生证明）";
            default -> kind.name();
        };
    }

    private static HistorySurfaceGroup historySurfaceGroup(TreeItem<String> modeRoot,
            java.util.Map<String, TreeItem<String>> groups, InteractionSurfaceEvent surface,
            boolean alwaysSurface) {
        boolean browser = surface.mode() == InteractionSurfaceEvent.Mode.BROWSER;
        String mode = browser ? "浏览器" : "桌面";
        String parentKey;
        TreeItem<String> parent;
        if (browser) {
            String runtimeKey = mode + "/runtime/" + surface.runtimeId();
            TreeItem<String> runtime = group(modeRoot, groups, runtimeKey,
                    (surface.applicationId().isBlank() ? "运行环境 " : "应用 " + surface.applicationId() + " · ")
                            + surface.runtimeId());
            parentKey = runtimeKey + "/context/" + surface.contextId();
            parent = group(runtime, groups, parentKey, "Context " + surface.contextId());
        } else {
            String applicationKey = mode + "/application/" + surface.applicationId().length() + ":" + surface.applicationId();
            TreeItem<String> application = group(modeRoot, groups, applicationKey,
                    surface.applicationId().isBlank() ? "应用身份未知" : "应用 " + surface.applicationId());
            if (nativeProcessRuntime(surface.runtimeId())) {
                parentKey = applicationKey + "/process/" + surface.runtimeId();
                parent = group(application, groups, parentKey, "进程实例 " + surface.runtimeId());
            } else {
                // Historical random runtime IDs identify sessions, never OS process instances.
                parentKey = applicationKey + "/unknown-runtime/" + surface.runtimeId()
                        + "/context/" + surface.contextId();
                parent = group(application, groups, parentKey, "进程身份未知 · 会话 " + surface.contextId());
            }
        }
        if (!alwaysSurface && surface.surfaceId().isBlank()) return new HistorySurfaceGroup(parent, parentKey);
        String key = parentKey + "/surface/" + surface.surfaceId();
        return new HistorySurfaceGroup(group(parent, groups, key,
                (browser ? "页面 " : "实际窗口 ") + surface.surfaceId()), key);
    }

    /** Only the host-produced ABI6+ process namespace identifies a process instance. */
    private static boolean nativeProcessRuntime(String runtimeId) {
        String[] parts = runtimeId.split(":", -1);
        if (parts.length != 5 || !parts[0].equals("desktop") || !parts[1].equals("process")
                || !(parts[2].equals("macos") || parts[2].equals("windows"))) return false;
        try {
            long processId = Long.parseLong(parts[3]);
            long instance = Long.parseUnsignedLong(parts[4]);
            return processId > 0 && instance != 0 && parts[3].equals(Long.toString(processId))
                    && parts[4].equals(Long.toUnsignedString(instance));
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static TreeItem<String> group(TreeItem<String> parent, java.util.Map<String, TreeItem<String>> groups,
            String key, String label) {
        return groups.computeIfAbsent(key, ignored -> {
            TreeItem<String> item = new TreeItem<>(label);
            parent.getChildren().add(item);
            return item;
        });
    }

    private static String describe(InteractionHistory.Entry entry) {
        StringBuilder text = new StringBuilder("时间：").append(entry.observedAt())
                .append("\n来源轮次：").append(entry.ownerRunId())
                .append("\n事件：").append(entry.kind()).append(" #").append(entry.eventSequence())
                .append("\n状态：").append(entry.status()).append("\n证据引用：").append(entry.evidenceRef());
        if (entry.surface() == null) {
            text.append("\n工具：").append(entry.tool()).append("\n调用：").append(entry.invocationId());
            for (var surface : entry.associatedSurfaces()) {
                text.append("\n观察关联（不是因果）：")
                        .append(surface.applicationId()).append(" · ").append(surface.surfaceId())
                        .append(" · 代次 ").append(surface.generation()).append(" · ").append(surface.kind());
                if (surface.mode() == InteractionSurfaceEvent.Mode.DESKTOP)
                    text.append("\n进程/历史运行环境：").append(surface.runtimeId())
                            .append("\n桌面会话：").append(surface.contextId())
                            .append("\n逻辑目标：").append(surface.logicalTargetId());
            }
        } else {
            var surface = entry.surface();
            text.append("\n运行环境：").append(surface.runtimeId()).append("\nContext/会话：").append(surface.contextId())
                    .append("\n页面/实际窗口：").append(surface.surfaceId()).append("\n文档：").append(surface.documentId())
                    .append("\n逻辑目标：").append(surface.logicalTargetId()).append("\n应用：").append(surface.applicationId())
                    .append("\n代次：").append(surface.generation()).append("\n内容修订：").append(surface.contentRevision())
                    .append("\n关系：").append(surface.relation()).append(" · ").append(surface.relationProof())
                    .append(surface.mode() == InteractionSurfaceEvent.Mode.DESKTOP
                            ? "\n原生父窗口（历史；空值表示未确认）：" : "\n已证明的 opener：").append(surface.relatedSurfaceId())
                    .append("\n关联调用（缓存检查点不代表本次采集）：").append(surface.observedDuringInvocationId())
                    .append("\n动作关联类型：").append(surface.causeProof())
                    .append(switch (surface.causeProof()) {
                        case DIRECT_CREATE -> "\n宿主直接创建调用：";
                        case EXPECTED_POPUP_MATCH -> "\n预注册弹窗等待匹配调用（不证明因果）：";
                        case OBSERVED_AFTER -> "\n操作后观察关联的来源调用（创建因果未知）：";
                        case UNKNOWN -> "\n创建调用未确认：";
                    })
                    .append(surface.causedByInvocationId())
                    .append(surface.mode() == InteractionSurfaceEvent.Mode.DESKTOP
                            ? "\n操作前来源实际窗口：" : "\n操作前来源页面：").append(surface.sourceSurfaceId())
                    .append("\n站点 origin：").append(surface.urlOrigin()).append("\nURL 摘要：").append(surface.urlHash());
        }
        return text.toString();
    }

    private static void showSource(Window owner, ChatService service, String sessionId, RunId runId) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("原始交互来源 · " + runId);
        if (owner != null) dialog.initOwner(owner);
        TreeItem<String> root = new TreeItem<>("正在读取原始步骤…");
        root.setExpanded(true);
        TreeView<String> tree = recordTree(root, "原始交互步骤");
        TextArea details = recordDetails("选择步骤，查看完整原始内容");
        tree.getSelectionModel().selectedItemProperty().addListener((observable, before, selected) ->
                details.setText(selected == null ? "" : selected.getValue()));
        var split = recordSplit(tree, details, "原始步骤", "来自所选交互事件的执行轮次", "步骤详情", "完整输入、输出与错误记录");
        VBox body = new VBox(18, header("原始交互来源", "轮次 " + runId), split);
        VBox.setVgrow(split, Priority.ALWAYS);
        body.setMinSize(0, 0);
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("关闭", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.setResizable(true);
        styleInspector(dialog, owner);
        dialog.setOnShown(ignored -> Thread.ofVirtual().name("javaclaw-interaction-source").start(() -> {
            try {
                var steps = service.sessionInteractionSteps(sessionId, runId);
                Platform.runLater(() -> {
                    if (!dialog.isShowing()) return;
                    root.setValue("轮次 " + runId);
                    for (var step : steps) {
                        var item = new TreeItem<>(step.kind() + " · " + step.state() + " · " + step.id());
                        item.getChildren().add(new TreeItem<>("输入：\n" + value(step.input())));
                        item.getChildren().add(new TreeItem<>("输出：\n" + value(step.output())));
                        if (step.error() != null) item.getChildren().add(new TreeItem<>("错误：" + step.error()));
                        root.getChildren().add(item);
                    }
                    if (steps.isEmpty()) root.getChildren().add(new TreeItem<>("没有可用的原始步骤记录"));
                });
            } catch (RuntimeException unavailable) {
                Platform.runLater(() -> { if (dialog.isShowing()) root.setValue("原始来源不可读或不属于当前会话"); });
            }
        }));
        dialog.showAndWait();
    }

    private static String value(com.fasterxml.jackson.databind.JsonNode node) {
        return node == null ? "—" : node.toPrettyString();
    }
}
