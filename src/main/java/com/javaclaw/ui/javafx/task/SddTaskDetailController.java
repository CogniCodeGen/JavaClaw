package com.javaclaw.ui.javafx.task;

import com.javaclaw.application.task.SddTaskApplicationService.Task;
import com.javaclaw.task.sdd.run.SddTaskState;
import com.javaclaw.task.sdd.spec.Capability;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;
import com.javaclaw.task.sdd.spec.TaskItem;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SDD 详情 Controller：把不可变任务快照投影到 FXML，不执行业务命令。 */
public final class SddTaskDetailController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] STAGES = {"提案", "规格", "设计", "拆解", "实现", "验收"};
    private static final Pattern LOG_LINE = Pattern.compile("^\\[([^\\]]+)]\\s*(.*)$");
    private static final int MAX_LOG_ROWS = 400;

    @FXML private Label titleLabel;
    @FXML private Label descriptionLabel;
    @FXML private Label stateBadge;
    @FXML private Arc donutArc;
    @FXML private Label donutPercent;
    @FXML private Label stageValue;
    @FXML private Label checklistValue;
    @FXML private Label tokenValue;
    @FXML private ProgressBar tokenBar;
    @FXML private Label tokenHint;
    @FXML private Label elapsedValue;
    @FXML private Label elapsedHint;
    @FXML private Label pipeDot0;
    @FXML private Label pipeDot1;
    @FXML private Label pipeDot2;
    @FXML private Label pipeDot3;
    @FXML private Label pipeDot4;
    @FXML private Label pipeDot5;
    @FXML private Label pipeName0;
    @FXML private Label pipeName1;
    @FXML private Label pipeName2;
    @FXML private Label pipeName3;
    @FXML private Label pipeName4;
    @FXML private Label pipeName5;
    @FXML private Region pipeLine0;
    @FXML private Region pipeLine1;
    @FXML private Region pipeLine2;
    @FXML private Region pipeLine3;
    @FXML private Region pipeLine4;
    @FXML private Button overviewTab;
    @FXML private Button acceptanceTab;
    @FXML private Button checklistTab;
    @FXML private Button logTab;
    @FXML private VBox overviewPane;
    @FXML private VBox acceptancePane;
    @FXML private VBox checklistPane;
    @FXML private VBox logPane;
    @FXML private VBox resultCard;
    @FXML private Label resultTitle;
    @FXML private Label resultContent;
    @FXML private VBox proposalPanel;
    @FXML private Label whyLabel;
    @FXML private ListView<String> changesList;
    @FXML private VBox outOfScopeCard;
    @FXML private Label outOfScopeLabel;
    @FXML private Label truthPathLabel;
    @FXML private Label overviewEmpty;
    @FXML private ListView<SddScenarioRow> scenarioList;
    @FXML private Label acceptanceEmpty;
    @FXML private ProgressBar checklistProgress;
    @FXML private Label checklistCounter;
    @FXML private ListView<TaskItem> checklistList;
    @FXML private Label checklistEmpty;
    @FXML private ListView<SddLogEntry> logList;

    private final SddDetailCellFactory cells;
    private final ObservableList<String> changes = FXCollections.observableArrayList();
    private final ObservableList<SddScenarioRow> scenarios = FXCollections.observableArrayList();
    private final ObservableList<TaskItem> checklist = FXCollections.observableArrayList();
    private final ObservableList<SddLogEntry> logs = FXCollections.observableArrayList();
    private List<Label> pipeDots;
    private List<Label> pipeNames;
    private List<Region> pipeLines;
    private Map<String, Button> tabs;
    private Map<String, VBox> panes;

    public SddTaskDetailController(SddDetailCellFactory cells) {
        this.cells = java.util.Objects.requireNonNull(cells, "cells");
    }

    @FXML
    private void initialize() {
        pipeDots = List.of(pipeDot0, pipeDot1, pipeDot2, pipeDot3, pipeDot4, pipeDot5);
        pipeNames = List.of(pipeName0, pipeName1, pipeName2, pipeName3, pipeName4, pipeName5);
        pipeLines = List.of(pipeLine0, pipeLine1, pipeLine2, pipeLine3, pipeLine4);
        tabs = Map.of("overview", overviewTab, "acceptance", acceptanceTab,
                "checklist", checklistTab, "log", logTab);
        panes = Map.of("overview", overviewPane, "acceptance", acceptancePane,
                "checklist", checklistPane, "log", logPane);
        changesList.setItems(changes);
        changesList.setCellFactory(ignored -> cells.change());
        scenarioList.setItems(scenarios);
        scenarioList.setCellFactory(ignored -> cells.scenario());
        checklistList.setItems(checklist);
        checklistList.setCellFactory(ignored -> cells.checklist());
        logList.setItems(logs);
        logList.setCellFactory(ignored -> cells.log());
        showTab("overview");
    }

    public void show(Task task, OpenSpecChange change) {
        titleLabel.setText(task.title());
        descriptionLabel.setText(text(task.description()) + "　·　工作目录 " + text(task.workDir()));
        stateBadge.setText(SddTaskFormat.badgeLabel(task.state()));
        stateBadge.getStyleClass().setAll("jc-badge", SddTaskFormat.badgeStyle(task.state()));
        donutPercent.setText(String.valueOf(task.progress()));
        donutArc.setLength(-360.0 * task.progress() / 100.0);
        donutArc.getStyleClass().remove("sdd-donut-arc-amber");
        if (task.state() == SddTaskState.NEEDS_HUMAN) {
            donutArc.getStyleClass().add("sdd-donut-arc-amber");
        }
        int stage = stageOf(task, change);
        renderStats(task, change, stage);
        renderPipeline(stage, task.state());
        renderOverview(task, change);
        renderScenarios(task, change);
        renderChecklist(change);
    }

    public void clear() {
        changes.clear();
        scenarios.clear();
        checklist.clear();
        logs.clear();
        showTab("overview");
    }

    public void clearLogs() { logs.clear(); }

    public void appendLog(String message) {
        Matcher matcher = LOG_LINE.matcher(message == null ? "" : message);
        String time = matcher.matches() ? matcher.group(1) : "";
        String text = matcher.matches() ? matcher.group(2) : message;
        SddLogEntry.Kind kind = text != null && text.contains("✓") ? SddLogEntry.Kind.OK
                : text != null && text.contains("⚠") ? SddLogEntry.Kind.WARN
                : text != null && text.contains("⚙") ? SddLogEntry.Kind.INFO
                : SddLogEntry.Kind.DEFAULT;
        logs.add(new SddLogEntry(time, text == null ? "" : text, kind));
        if (logs.size() > MAX_LOG_ROWS) logs.remove(0, logs.size() - MAX_LOG_ROWS);
        logList.scrollTo(logs.size() - 1);
    }

    @FXML private void showOverview() { showTab("overview"); }
    @FXML private void showAcceptance() { showTab("acceptance"); }
    @FXML private void showChecklist() { showTab("checklist"); }
    @FXML private void showLog() { showTab("log"); }

    private void showTab(String selected) {
        tabs.forEach((key, button) -> {
            button.getStyleClass().remove("sdd-tab-active");
            if (key.equals(selected)) button.getStyleClass().add("sdd-tab-active");
        });
        panes.forEach((key, pane) -> visible(pane, key.equals(selected)));
    }

    private void renderStats(Task task, OpenSpecChange change, int stage) {
        stageValue.setText(task.state() == SddTaskState.COMPLETED
                ? "已完成" : STAGES[Math.min(stage, STAGES.length - 1)]);
        stageValue.getStyleClass().removeAll(
                "sdd-stat-value-brand", "sdd-stat-value-amber", "sdd-stat-value-danger");
        if (task.state() == SddTaskState.RUNNING || task.state() == SddTaskState.COMPLETED) {
            stageValue.getStyleClass().add("sdd-stat-value-brand");
        } else if (task.state() == SddTaskState.NEEDS_HUMAN) {
            stageValue.getStyleClass().add("sdd-stat-value-amber");
        } else if (task.state() == SddTaskState.FAILED) {
            stageValue.getStyleClass().add("sdd-stat-value-danger");
        }
        long done = change == null ? 0 : change.tasks().stream().filter(TaskItem::done).count();
        checklistValue.setText(change == null || change.tasks().isEmpty()
                ? "—" : done + "/" + change.tasks().size());
        tokenValue.setText(SddTaskFormat.tokens(task.totalTokens()));
        boolean limited = task.tokenBudget() > 0;
        double tokenRatio = limited ? task.totalTokens() / (double) task.tokenBudget() : 0;
        visible(tokenBar, limited);
        tokenBar.setProgress(Math.min(1, tokenRatio));
        tokenBar.getStyleClass().remove("sdd-stat-bar-danger");
        if (tokenRatio > 0.85) tokenBar.getStyleClass().add("sdd-stat-bar-danger");
        tokenHint.setText(limited ? "预算 " + SddTaskFormat.tokens(task.tokenBudget()) : "预算不限");
        String[] elapsed = elapsed(task);
        elapsedValue.setText(elapsed[0]);
        elapsedHint.setText(elapsed[1]);
    }

    private void renderPipeline(int stage, SddTaskState state) {
        boolean paused = state == SddTaskState.NEEDS_HUMAN || state == SddTaskState.PAUSED;
        for (int i = 0; i < STAGES.length; i++) {
            Label dot = pipeDots.get(i);
            Label name = pipeNames.get(i);
            dot.getStyleClass().removeAll("sdd-pipe-dot-done", "sdd-pipe-dot-active",
                    "sdd-pipe-dot-amber", "sdd-pipe-dot-pending");
            name.getStyleClass().removeAll(
                    "sdd-pipe-name-done", "sdd-pipe-name-active", "sdd-pipe-name-pending");
            if (i < stage) {
                dot.setText("✓");
                dot.getStyleClass().add("sdd-pipe-dot-done");
                name.getStyleClass().add("sdd-pipe-name-done");
            } else if (i == stage && state != SddTaskState.COMPLETED) {
                dot.setText(paused ? "‖" : String.valueOf(i + 1));
                dot.getStyleClass().add(paused ? "sdd-pipe-dot-amber" : "sdd-pipe-dot-active");
                name.getStyleClass().add("sdd-pipe-name-active");
            } else {
                dot.setText(String.valueOf(i + 1));
                dot.getStyleClass().add("sdd-pipe-dot-pending");
                name.getStyleClass().add("sdd-pipe-name-pending");
            }
        }
        for (int i = 0; i < pipeLines.size(); i++) {
            pipeLines.get(i).getStyleClass().remove("sdd-pipe-line-done");
            if (i < stage) pipeLines.get(i).getStyleClass().add("sdd-pipe-line-done");
        }
    }

    private void renderOverview(Task task, OpenSpecChange change) {
        boolean hasResult = task.result() != null && !task.result().isBlank();
        visible(resultCard, hasResult);
        boolean failed = task.state() == SddTaskState.FAILED;
        resultCard.getStyleClass().remove("sdd-result-card-failed");
        resultTitle.getStyleClass().remove("sdd-result-title-failed");
        if (failed) {
            resultCard.getStyleClass().add("sdd-result-card-failed");
            resultTitle.getStyleClass().add("sdd-result-title-failed");
        }
        if (hasResult) {
            resultTitle.setText(failed ? "任务失败"
                    : task.state() == SddTaskState.COMPLETED ? "任务结果" : "等待人工处理");
            resultContent.setText(task.result());
        }
        Proposal proposal = change == null ? null : change.proposal();
        visible(proposalPanel, proposal != null);
        visible(overviewEmpty, proposal == null);
        if (proposal == null) return;
        whyLabel.setText(text(proposal.why()));
        changes.setAll(splitLines(proposal.whatChanges()));
        boolean hasOutOfScope = proposal.outOfScope() != null && !proposal.outOfScope().isBlank();
        visible(outOfScopeCard, hasOutOfScope);
        outOfScopeLabel.setText(hasOutOfScope ? proposal.outOfScope() : "");
        truthPathLabel.setText("H2 OpenSpec · " + change.slug());
    }

    private void renderScenarios(Task task, OpenSpecChange change) {
        scenarios.clear();
        if (change != null) {
            for (Capability capability : change.capabilities()) {
                for (var scenario : capability.allScenarios()) {
                    scenarios.add(new SddScenarioRow(
                            capability.name(), scenario, task.state() == SddTaskState.COMPLETED));
                }
            }
        }
        visible(acceptanceEmpty, scenarios.isEmpty());
        visible(scenarioList, !scenarios.isEmpty());
        acceptanceTab.setText(scenarios.isEmpty() ? "验收场景" : "验收场景 " + scenarios.size());
    }

    private void renderChecklist(OpenSpecChange change) {
        checklist.setAll(change == null ? List.of() : change.tasks());
        visible(checklistEmpty, checklist.isEmpty());
        visible(checklistList, !checklist.isEmpty());
        long done = checklist.stream().filter(TaskItem::done).count();
        checklistProgress.setProgress(checklist.isEmpty() ? 0 : done / (double) checklist.size());
        checklistCounter.setText(done + "/" + checklist.size());
    }

    private static int stageOf(Task task, OpenSpecChange change) {
        if (task.state() == SddTaskState.COMPLETED) return STAGES.length;
        if (change == null || change.proposal() == null) return 0;
        if (change.capabilities().isEmpty()) return 1;
        if (change.tasks().isEmpty()) return change.design() == null || change.design().isBlank() ? 2 : 3;
        return change.allTasksDone() ? 5 : 4;
    }

    private static String[] elapsed(Task task) {
        try {
            LocalDateTime start = LocalDateTime.parse(task.createdAt(), TS);
            LocalDateTime end = task.state() == SddTaskState.RUNNING
                    ? LocalDateTime.now() : LocalDateTime.parse(task.updatedAt(), TS);
            Duration duration = Duration.between(start, end);
            if (duration.isNegative()) return new String[]{"—", ""};
            String value = duration.toDays() > 0 ? duration.toDays() + "d " + duration.toHoursPart() + "h"
                    : duration.toHours() > 0 ? duration.toHours() + "h " + duration.toMinutesPart() + "m"
                    : duration.toMinutes() + "m " + duration.toSecondsPart() + "s";
            return new String[]{value, task.state() == SddTaskState.RUNNING ? "运行中" : "累计"};
        } catch (RuntimeException failure) {
            return new String[]{"—", ""};
        }
    }

    private static List<String> splitLines(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        value.lines().map(String::strip).filter(line -> !line.isEmpty())
                .map(line -> line.replaceFirst("^[-*•]\\s+", "")
                        .replaceFirst("^\\d+[.、)）]\\s*", ""))
                .filter(line -> !line.isEmpty()).forEach(result::add);
        return result;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }
}
