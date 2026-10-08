package com.javaclaw.chat;

import com.javaclaw.agent.ChatService;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.InteractionHistory;
import com.javaclaw.framework.api.InteractionSurfaceEvent;
import com.javaclaw.framework.api.RunId;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;

/** Expandable read-only view of the durable Thread → Turn → Step hierarchy. */
final class ChatExecutionInspector {
    private static final String SHORTCUT_HELP = "Cmd/Ctrl+1/2/3 切页；Cmd/Ctrl+Shift+E 导出指标。";

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
        TreeView<String> tree = new TreeView<>(thread);
        tree.setCellFactory(ignored -> new javafx.scene.control.TreeCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.length() > 140 ? item.substring(0, 140) + "…" : item);
            }
        });
        javafx.scene.control.TextArea details = new javafx.scene.control.TextArea();
        details.setEditable(false);
        details.setWrapText(true);
        details.setPromptText("选择步骤输入、输出或来源，查看并复制完整记录");
        tree.getSelectionModel().selectedItemProperty().addListener((observable, before, selected) ->
                details.setText(selected == null ? "" : selected.getValue()));
        var content = new javafx.scene.control.SplitPane(tree, details);
        content.setPrefSize(1000, 600);
        content.setDividerPositions(0.45);
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("会话执行记录");
        if (owner != null) dialog.initOwner(owner);
        var tabs = new TabPane();
        Tab executions = new Tab("轮次与步骤", content);
        executions.setClosable(false);
        Tab interactions = new Tab("浏览器与桌面交互");
        interactions.setClosable(false);
        Tab metrics = new Tab("用量与结果");
        metrics.setClosable(false);
        tabs.getTabs().addAll(executions, interactions, metrics);
        tabs.setAccessibleHelp(SHORTCUT_HELP);
        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().setAccessibleHelp(SHORTCUT_HELP);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
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
        TreeView<String> tree = new TreeView<>(root);
        javafx.scene.control.TextArea details = new javafx.scene.control.TextArea();
        details.setEditable(false);
        details.setWrapText(true);
        details.setPromptText("选择事件查看身份、关系、回执状态和原始证据引用");
        java.util.Map<TreeItem<String>, InteractionHistory.Entry> sources = new java.util.IdentityHashMap<>();
        Button source = new Button("查看原始轮次与步骤");
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
        Button refresh = new Button("刷新");
        var split = new javafx.scene.control.SplitPane(tree, details);
        split.setDividerPositions(0.45);
        split.setPrefSize(1000, 600);
        BorderPane panel = new BorderPane(split);
        var note = new javafx.scene.control.Label("持久历史不等于当前画面；“观察期间”不表示动作导致新窗口。桌面父窗未知时保持未知。");
        note.setWrapText(true);
        var controls = new javafx.scene.layout.VBox(6, note, new HBox(8, refresh, source));
        controls.setPadding(new javafx.geometry.Insets(8));
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
        service.sessionTurns(sessionId).forEach(turn -> turns.getItems().add(turn.id()));
        if (!turns.getItems().isEmpty()) turns.getSelectionModel().selectLast();
        javafx.scene.control.TextArea details = new javafx.scene.control.TextArea();
        details.setEditable(false);
        details.setWrapText(true);
        details.setPromptText("选择轮次查看主代理、子代理及全部实际 provider 尝试的合计用量");
        Button refresh = new Button("刷新指标");
        export.setDisable(true);
        var note = new javafx.scene.control.Label("只读指标，不发起模型调用。Token 和金额只合计已记录部分；缺失用量、报价及提示长度单独计数。\n" + SHORTCUT_HELP);
        note.setAccessibleHelp(SHORTCUT_HELP);
        note.setWrapText(true);
        var controls = new javafx.scene.layout.VBox(6, note, new HBox(8, turns, refresh, export));
        controls.setPadding(new javafx.geometry.Insets(8));
        BorderPane panel = new BorderPane(details);
        panel.setTop(controls);
        java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicReference<String> exported = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable load = () -> {
            RunId selected = turns.getValue();
            if (selected == null) return;
            long requested = revision.incrementAndGet();
            refresh.setDisable(true);
            export.setDisable(true);
            Thread.ofVirtual().name("javaclaw-interaction-metrics").start(() -> {
                try {
                    var metrics = service.sessionInteractionMetrics(sessionId, selected);
                    String serialized = metricsJson(metrics).toPrettyString();
                    String summary = "轮次：" + metrics.parentRunId() + " · " + metrics.state()
                            + "\n业务耗时：" + metrics.elapsedMillis() + " ms · 执行链子轮次全部终结：" + metrics.terminalFamily()
                            + "\n验收结果：" + (metrics.taskResult() == null ? "尚无结果" : metrics.taskResult().outcome())
                            + " · 满足/未满足条件：" + metrics.satisfiedCriterionCount() + "/" + metrics.unmetCriterionCount()
                            + " · 证据引用：" + metrics.evidenceReferenceCount()
                            + "\nprovider 尝试（执行链/关联维护/总计）：" + metrics.executionFamily().providerAttempts() + "/"
                            + metrics.associatedMaintenance().providerAttempts() + "/" + metrics.aggregate().providerAttempts()
                            + "\n执行链主轮/子轮尝试：" + metrics.direct().providerAttempts() + "/" + metrics.descendants().providerAttempts()
                            + " · 关联维护轮次：" + metrics.associatedMaintenanceRuns().size()
                            + "\n用量为读取时快照；之后仍可能新增维护或迟到用量。"
                            + "\n合计已记录输入/输出 Token：" + metrics.aggregate().inputTokens() + "/" + metrics.aggregate().outputTokens()
                            + " · 用量未知尝试：" + metrics.aggregate().unknownUsageAttempts()
                            + "\n已知估算金额 CNY：" + metrics.aggregate().estimatedCostCny()
                            + " · 报价未知尝试：" + metrics.aggregate().unknownCostAttempts()
                            + "\n委派/模式切换：" + metrics.delegationCalls() + "/" + metrics.modeSwitches()
                            + " · 澄清/审批事件：" + metrics.clarificationRequests() + "/" + metrics.approvalRequests()
                            + "\n\n" + serialized;
                    Platform.runLater(() -> {
                        if (!dialog.isShowing() || requested != revision.get()) return;
                        details.setText(summary);
                        exported.set(serialized);
                        refresh.setDisable(false);
                        export.setDisable(false);
                    });
                } catch (RuntimeException unavailable) {
                    Platform.runLater(() -> {
                        if (!dialog.isShowing() || requested != revision.get()) return;
                        details.setText("指标暂不可读或来源不属于当前会话。");
                        refresh.setDisable(false);
                    });
                }
            });
        };
        turns.valueProperty().addListener((observable, before, selected) -> load.run());
        refresh.setOnAction(ignored -> load.run());
        export.setOnAction(ignored -> {
            if (exported.get() == null) return;
            var chooser = new javafx.stage.FileChooser();
            chooser.setTitle("导出交互用量指标");
            chooser.setInitialFileName("interaction-metrics-" + turns.getValue() + ".json");
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
            var file = chooser.showSaveDialog(dialog.getDialogPane().getScene().getWindow());
            if (file == null) return;
            try { java.nio.file.Files.writeString(file.toPath(), exported.get(), java.nio.charset.StandardCharsets.UTF_8); }
            catch (java.io.IOException failure) {
                var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR, "无法写入所选指标文件。", ButtonType.OK);
                alert.initOwner(dialog.getDialogPane().getScene().getWindow());
                alert.showAndWait();
            }
        });
        dialog.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_SHOWN, ignored -> load.run());
        return panel;
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
        for (var entry : history.entries()) {
            String mode = entry.mode() == InteractionSurfaceEvent.Mode.BROWSER ? "浏览器" : "桌面";
            TreeItem<String> modeRoot = group(root, groups, mode, mode);
            TreeItem<String> parent;
            if (entry.surface() != null) {
                parent = historySurfaceGroup(modeRoot, groups, entry.surface(), false).item();
            } else {
                TreeItem<String> actions = group(modeRoot, groups, mode + "/actions", "工具动作与回执");
                TreeItem<String> turn = group(actions, groups, mode + "/run/" + entry.ownerRunId(), "轮次 " + entry.ownerRunId());
                parent = group(turn, groups, mode + "/run/" + entry.ownerRunId() + "/" + entry.invocationId(),
                        entry.tool() + " · " + entry.invocationId());
            }
            TreeItem<String> item = new TreeItem<>(entry.observedAt() + " · " + entry.kind() + " · " + entry.status());
            sources.put(item, entry);
            parent.getChildren().add(item);
            if (entry.surface() == null) {
                for (var surface : entry.associatedSurfaces()) {
                    HistorySurfaceGroup window = historySurfaceGroup(modeRoot, groups, surface, true);
                    TreeItem<String> related = group(window.item(), groups, window.key() + "/actions", "观察关联的动作（不表示因果）");
                    TreeItem<String> action = new TreeItem<>(entry.observedAt() + " · " + entry.tool() + " · " + entry.status());
                    related.getChildren().add(action);
                    sources.put(action, entry);
                }
            }
        }
    }

    private record HistorySurfaceGroup(TreeItem<String> item, String key) { }

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

    /** Only the new host-produced ABI6 process namespace identifies a process instance. */
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
                    .append("\n已证明的 opener：").append(surface.relatedSurfaceId())
                    .append("\n关联调用（缓存检查点不代表本次采集）：").append(surface.observedDuringInvocationId())
                    .append("\n动作关联类型：").append(surface.causeProof())
                    .append(switch (surface.causeProof()) {
                        case DIRECT_CREATE -> "\n宿主直接创建调用：";
                        case EXPECTED_POPUP_MATCH -> "\n预注册弹窗等待匹配调用（不证明因果）：";
                        case UNKNOWN -> "\n创建调用未确认：";
                    })
                    .append(surface.causedByInvocationId())
                    .append("\n操作前来源页面：").append(surface.sourceSurfaceId())
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
        TreeView<String> tree = new TreeView<>(root);
        javafx.scene.control.TextArea details = new javafx.scene.control.TextArea();
        details.setEditable(false);
        details.setWrapText(true);
        tree.getSelectionModel().selectedItemProperty().addListener((observable, before, selected) ->
                details.setText(selected == null ? "" : selected.getValue()));
        var split = new javafx.scene.control.SplitPane(tree, details);
        split.setPrefSize(1000, 600);
        dialog.getDialogPane().setContent(split);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.setResizable(true);
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
