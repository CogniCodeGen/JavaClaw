package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.app.UIHelper;
import com.javaclaw.schedule.ScheduleManager;
import com.javaclaw.schedule.ScheduledTask;
import com.javaclaw.task.TaskNotificationChannel;
import com.javaclaw.ui.javafx.control.ToggleSwitch;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 定时任务管理界面（模态对话框）。
 *
 * <p>左侧任务列表（启用点 + 触发描述 + 下次运行），右侧围绕选中任务渲染头部状态卡
 * （触发 / 下次 / 上次 / 累计·失败 + 启停开关）、触发方式编辑、执行内容、完成通知与
 * 结构化运行历史表。沿用四弹窗统一骨架与令牌化样式（{@code -jc-*}），不硬编码颜色。</p>
 *
 * @author JavaClaw
 */
public class ScheduleView {

    private static final Logger log = LoggerFactory.getLogger(ScheduleView.class);

    private static final DateTimeFormatter NEXT_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final Stage stage;
    private final ScheduleManager scheduleManager;

    private VBox taskListBox;
    private Label listSubtitle;
    private StackPane contentArea;
    private VBox emptyPanel;
    private VBox detailPanel;
    private String selectedTaskId;

    // ---- 编辑控件 ----
    private TextField nameField;
    private TextArea promptArea;
    private String currentTriggerType = "interval";
    private VBox triggerFieldsBox;
    private TextField onceDateField;
    private TextField onceTimeField;
    private TextField intervalValueField;
    private ComboBox<String> intervalUnitCombo;
    private TextField dailyTimeField;
    private TextField cronField;
    private Label cronParseHint;
    private ToggleSwitch notifyToggle;
    private ComboBox<String> channelCombo;

    // ---- 头部状态卡可刷新引用 ----
    private Label headerStateLabel;
    private ToggleSwitch headerToggle;
    private Label statTriggerVal;
    private Label statNextVal;
    private Label statNextSub;
    private Label statLastVal;
    private Label statLastBadge;
    private Label statRunsVal;
    private VBox historyBox;
    private Label statusHint;

    /** 实时刷新「下次运行」倒计时与运行状态的轻量定时器 */
    private javafx.animation.Timeline statusTimer;

    public ScheduleView(Stage owner) {
        this.scheduleManager = ScheduleManager.getInstance();
        this.stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setTitle("定时任务");
        stage.setResizable(true);
        buildUI();

        // 执行日志回调（Quartz worker 线程 → 切回 FX 线程刷新日志区已无独立日志区，改写状态提示）
        scheduleManager.setOnTaskLog((taskName, message) -> Platform.runLater(() -> {
            if (statusHint != null) statusHint.setText("[" + taskName + "] " + message);
        }));
        // 执行开始：翻成运行中 + 刷新列表/头部
        scheduleManager.setOnTaskExecutionStart(taskId -> Platform.runLater(() -> {
            refreshTaskList();
            if (taskId.equals(selectedTaskId)) refreshDetailStatus();
        }));
        // 执行完成：刷新列表/头部/历史
        scheduleManager.setOnTaskExecutionComplete(taskId -> Platform.runLater(() -> {
            refreshTaskList();
            if (taskId.equals(selectedTaskId)) {
                ScheduledTask t = scheduleManager.getTask(taskId);
                if (t != null) {
                    refreshDetailStatus();
                    refreshHistory(t);
                    if (statusHint != null) {
                        statusHint.setText("上次执行 " + t.getLastRunStatus() + " · " + t.getLastDuration());
                    }
                }
            }
        }));
    }

    // ==================== 骨架 ====================

    private void buildUI() {
        // ----- 左侧 -----
        Label title = new Label("定时任务");
        title.getStyleClass().add("modal-left-title");
        title.setPadding(new Insets(18, 16, 4, 16));

        listSubtitle = new Label();
        listSubtitle.getStyleClass().add("sec-hint");
        listSubtitle.setPadding(new Insets(0, 16, 8, 16));

        Button addBtn = new Button("＋ 新建定时任务");
        addBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setOnAction(e -> onCreateTask());
        VBox addRow = new VBox(addBtn);
        addRow.setPadding(new Insets(0, 12, 10, 12));

        taskListBox = new VBox(4);
        taskListBox.setPadding(new Insets(2, 10, 8, 10));
        ScrollPane listScroll = new ScrollPane(taskListBox);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.getStyleClass().add("settings-scroll-pane");
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        VBox leftPane = new VBox(title, listSubtitle, addRow, listScroll);
        leftPane.getStyleClass().add("modal-left-pane");
        leftPane.setPrefWidth(264);
        leftPane.setMinWidth(220);

        // ----- 右侧 -----
        contentArea = new StackPane();
        contentArea.getStyleClass().add("modal-content-area");
        contentArea.setPadding(new Insets(20, 24, 12, 24));

        emptyPanel = buildEmptyPanel();
        detailPanel = new VBox();
        detailPanel.setVisible(false);
        detailPanel.setManaged(false);
        ScrollPane detailScroll = new ScrollPane(detailPanel);
        detailScroll.setFitToWidth(true);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.getStyleClass().add("settings-scroll-pane");
        detailScroll.setVisible(false);
        detailScroll.setManaged(false);
        this.detailScroll = detailScroll;

        contentArea.getChildren().addAll(emptyPanel, detailScroll);

        // ----- 页脚 -----
        Button runNowBtn = new Button("▶ 立即运行一次");
        runNowBtn.getStyleClass().addAll("jc-btn", "jc-btn-soft", "jc-btn-sm");
        runNowBtn.setOnAction(e -> onRunNow());
        this.runNowBtn = runNowBtn;

        statusHint = new Label("");
        statusHint.getStyleClass().add("sec-hint");
        statusHint.setMaxWidth(360);

        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);

        Button closeBtn = new Button("关闭");
        closeBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost");
        closeBtn.setOnAction(e -> stage.close());

        Button saveBtn = new Button("保存");
        saveBtn.getStyleClass().addAll("jc-btn", "jc-btn-save");
        saveBtn.setOnAction(e -> onSaveTask());
        this.saveBtn = saveBtn;

        HBox foot = new HBox(10, runNowBtn, statusHint, footSpacer, closeBtn, saveBtn);
        foot.setAlignment(Pos.CENTER_LEFT);
        foot.getStyleClass().add("modal-foot");

        VBox rightPane = new VBox(contentArea, foot);
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        HBox mainLayout = new HBox(leftPane, rightPane);
        mainLayout.getStyleClass().add("settings-root");

        Scene scene = new Scene(mainLayout, 960, 680);
        applyStylesheets(scene);
        stage.setScene(scene);
        refreshTaskList();
        updateFootButtons();
    }

    private ScrollPane detailScroll;
    private Button runNowBtn;
    private Button saveBtn;

    private VBox buildEmptyPanel() {
        Label icon = new Label("⏰");
        icon.getStyleClass().add("empty-state-icon");
        Label text = new Label("选择或新建一个定时任务");
        text.getStyleClass().add("empty-state-text");
        Label hint = new Label("定时任务可按一次性、固定间隔、每日定时或 Cron 触发；\n触发时把配置的提示词发送给智能体自动执行。");
        hint.getStyleClass().addAll("sec-hint", "empty-state-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(340);
        VBox panel = new VBox(12, icon, text, hint);
        panel.setAlignment(Pos.CENTER);
        return panel;
    }

    // ==================== 列表 ====================

    private void refreshTaskList() {
        taskListBox.getChildren().clear();
        List<ScheduledTask> all = scheduleManager.getAllTasks();
        long active = all.stream().filter(ScheduledTask::isEnabled).count();
        listSubtitle.setText(active + " 个启用 · 共 " + all.size() + " 个");

        if (all.isEmpty()) {
            Label empty = new Label("暂无定时任务");
            empty.getStyleClass().add("sec-hint");
            empty.setPadding(new Insets(16, 0, 0, 6));
            taskListBox.getChildren().add(empty);
            return;
        }
        for (ScheduledTask t : all) {
            taskListBox.getChildren().add(createTaskRow(t));
        }
    }

    private VBox createTaskRow(ScheduledTask task) {
        boolean running = scheduleManager.isRunning(task.getId());
        boolean on = task.getId().equals(selectedTaskId);

        Label dot = new Label(task.isEnabled() || running ? "●" : "○");
        dot.getStyleClass().add(running ? "status-dot-running"
                : (task.isEnabled() ? "status-dot-enabled" : "status-dot-disabled"));

        Label name = new Label(task.getName());
        name.getStyleClass().add("skill-list-name");
        name.setMaxWidth(150);

        HBox line1 = new HBox(7, dot, name);
        line1.setAlignment(Pos.CENTER_LEFT);
        if (running) {
            Label tag = new Label("运行中");
            tag.getStyleClass().addAll("jc-badge", "jc-badge-running");
            tag.setStyle("-fx-font-size:9.5px; -fx-padding:1 7 1 7;");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            line1.getChildren().addAll(sp, tag);
        } else if ("fail".equals(lastStateKey(task))) {
            Label warn = new Label("⚠");
            warn.getStyleClass().add("status-dot-disabled");
            warn.setStyle("-fx-text-fill:#EF4444;");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            line1.getChildren().addAll(sp, warn);
        }

        Label trig = new Label("⏱ " + task.describeTrigger());
        trig.getStyleClass().add("schedule-row-meta");

        Label next = new Label(nextLineText(task));
        next.getStyleClass().add("schedule-row-sub");
        next.setMaxWidth(220);

        VBox row = new VBox(6, line1, trig, next);
        row.getStyleClass().add("schedule-list-row");
        if (on) row.getStyleClass().add("schedule-list-row-selected");
        row.setOpacity(task.isEnabled() ? 1.0 : 0.65);
        row.setOnMouseClicked(e -> {
            selectedTaskId = task.getId();
            refreshTaskList();
            showDetail(task);
        });
        return row;
    }

    private String nextLineText(ScheduledTask task) {
        if (scheduleManager.isRunning(task.getId())) return "执行中…";
        if (!task.isEnabled()) return "已暂停";
        LocalDateTime n = scheduleManager.getNextFireTime(task.getId());
        if (n == null) return "等待调度…";
        return "下次 " + n.format(NEXT_FMT) + " · " + formatRelative(n);
    }

    private String lastStateKey(ScheduledTask t) {
        return "失败".equals(t.getLastRunStatus()) ? "fail" : ("成功".equals(t.getLastRunStatus()) ? "ok" : "");
    }

    // ==================== 详情 ====================

    private void showDetail(ScheduledTask task) {
        emptyPanel.setVisible(false);
        emptyPanel.setManaged(false);
        detailScroll.setVisible(true);
        detailScroll.setManaged(true);
        detailPanel.setVisible(true);
        detailPanel.setManaged(true);
        updateFootButtons();

        detailPanel.getChildren().clear();
        detailPanel.setSpacing(12);
        detailPanel.setPadding(new Insets(2));

        currentTriggerType = task.getTriggerType() == null ? "interval" : task.getTriggerType();

        detailPanel.getChildren().addAll(
                buildHeaderCard(task),
                buildTriggerSection(task),
                new Separator(),
                buildContentSection(task),
                new Separator(),
                buildNotifySection(task),
                new Separator(),
                buildHistorySection(task),
                buildDeleteRow());

        refreshDetailStatus();
    }

    /** 头部状态卡：名称（可编辑）+ 启停开关 + 4 个 Stat。 */
    private VBox buildHeaderCard(ScheduledTask task) {
        nameField = new TextField(task.getName());
        nameField.getStyleClass().add("schedule-name-field");
        nameField.setPromptText("任务名称");
        HBox.setHgrow(nameField, Priority.ALWAYS);
        nameField.setMaxWidth(Double.MAX_VALUE);
        TextField name = nameField;

        headerStateLabel = new Label();
        headerStateLabel.getStyleClass().add("sec-hint");

        headerToggle = new ToggleSwitch(task.isEnabled());
        headerToggle.selectedProperty().addListener((o, was, now) -> onToggleEnabled(now));

        HBox head = new HBox(10, name, headerStateLabel, headerToggle);
        head.setAlignment(Pos.CENTER_LEFT);

        statTriggerVal = new Label();
        statNextVal = new Label();
        statNextSub = new Label();
        statLastVal = new Label();
        statLastBadge = new Label();
        statLastBadge.getStyleClass().add("jc-badge");
        statRunsVal = new Label();

        HBox stats = new HBox(24,
                buildStat("触发", statTriggerVal, null, null),
                buildStat("下次运行", statNextVal, statNextSub, null),
                buildStat("上次运行", statLastVal, null, statLastBadge),
                buildStat("累计 / 失败", statRunsVal, null, null));
        stats.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(14, head, stats);
        card.getStyleClass().add("jc-card");
        card.setPadding(new Insets(14, 16, 14, 16));
        return card;
    }

    private VBox buildStat(String label, Label value, Label sub, Label badge) {
        value.getStyleClass().add("jc-stat-value");
        Label lab = new Label(label);
        lab.getStyleClass().add("jc-stat-label");
        HBox valRow = new HBox(6, value);
        valRow.setAlignment(Pos.CENTER_LEFT);
        if (badge != null) valRow.getChildren().add(badge);
        VBox box = new VBox(4, lab, valRow);
        if (sub != null) {
            sub.getStyleClass().add("jc-stat-sub");
            box.getChildren().add(sub);
        }
        return box;
    }

    /** 触发方式：Seg 选择 + 动态字段区。 */
    private VBox buildTriggerSection(ScheduledTask task) {
        Label gt = new Label("触发方式");
        gt.getStyleClass().add("grp-title");

        ToggleGroup grp = new ToggleGroup();
        HBox seg = new HBox(3);
        seg.getStyleClass().add("seg-container");
        String[][] opts = {{"once", "一次性"}, {"interval", "间隔"}, {"daily", "每日"}, {"cron", "Cron"}};
        for (String[] o : opts) {
            ToggleButton b = new ToggleButton(o[1]);
            b.getStyleClass().add("seg-btn");
            b.setToggleGroup(grp);
            b.setUserData(o[0]);
            b.setSelected(o[0].equals(currentTriggerType));
            b.setOnAction(e -> {
                if (b.isSelected()) {
                    currentTriggerType = o[0];
                    populateTriggerFields(task);
                } else {
                    b.setSelected(true); // 不允许全空
                }
            });
            seg.getChildren().add(b);
        }

        triggerFieldsBox = new VBox(8);
        triggerFieldsBox.setPadding(new Insets(10, 0, 0, 0));
        populateTriggerFields(task);

        return new VBox(0, gt, seg, triggerFieldsBox);
    }

    private void populateTriggerFields(ScheduledTask task) {
        triggerFieldsBox.getChildren().clear();
        switch (currentTriggerType) {
            case "once" -> {
                String dt = task.getOnceDateTime() == null ? "" : task.getOnceDateTime();
                String d = "", tm = "";
                if (dt.contains(" ")) { String[] p = dt.split(" ", 2); d = p[0]; tm = p[1]; }
                onceDateField = labeledField("日期", d.isBlank() ? "2026-06-10" : d, 130);
                onceTimeField = labeledField("时间", tm.isBlank() ? "08:30" : tm, 90);
                HBox row = new HBox(12, wrap(onceDateField, "日期"), wrap(onceTimeField, "时间"));
                triggerFieldsBox.getChildren().add(row);
            }
            case "interval" -> {
                int v = task.getIntervalValue() > 0 ? task.getIntervalValue()
                        : Math.max(1, task.getIntervalMinutes());
                intervalValueField = labeledField("每隔", String.valueOf(v), 90);
                intervalUnitCombo = new ComboBox<>();
                intervalUnitCombo.getItems().addAll("分钟", "小时", "天");
                intervalUnitCombo.setValue(unitLabel(task.getIntervalUnit()));
                VBox unitWrap = new VBox(4, smallLabel("单位"), intervalUnitCombo);
                HBox row = new HBox(12, wrap(intervalValueField, "每隔"), unitWrap);
                row.setAlignment(Pos.BOTTOM_LEFT);
                triggerFieldsBox.getChildren().add(row);
            }
            case "daily" -> {
                dailyTimeField = labeledField("时间", task.getDailyTime() == null || task.getDailyTime().isBlank()
                        ? "09:00" : task.getDailyTime(), 100);
                HBox row = new HBox(8, wrap(dailyTimeField, "时间"), smallHint("（24 小时制，如 09:30）"));
                row.setAlignment(Pos.BOTTOM_LEFT);
                triggerFieldsBox.getChildren().add(row);
            }
            case "cron" -> {
                cronField = labeledField("Cron 表达式", task.getCronExpression() == null ? "" : task.getCronExpression(), 280);
                cronField.textProperty().addListener((o, a, b) -> updateCronHint(b));
                cronParseHint = smallHint("");
                updateCronHint(cronField.getText());
                String[][] presets = {
                        {"0 0 9 * * ?", "每天 09:00"}, {"0 0 18 ? * MON", "每周一 18:00"},
                        {"0 0 * * * ?", "每小时"}, {"0 0/15 * * * ?", "每 15 分"}};
                FlowPane chipFlow = new FlowPane(6, 6);
                for (String[] p : presets) {
                    Label chip = new Label(p[1] + " → " + p[0]);
                    chip.getStyleClass().add("jc-chip");
                    chip.setOnMouseClicked(e -> { cronField.setText(p[0]); updateCronHint(p[0]); });
                    chipFlow.getChildren().add(chip);
                }
                triggerFieldsBox.getChildren().addAll(wrap(cronField, "Cron 表达式"), cronParseHint, chipFlow);
            }
        }
    }

    private void updateCronHint(String expr) {
        if (cronParseHint == null) return;
        cronParseHint.setText("Quartz 6 段：秒 分 时 日 月 周（日/周二选一用 ?）" + describeCron(expr));
    }

    private String describeCron(String expr) {
        if (expr == null || expr.isBlank()) return "";
        return org.quartz.CronExpression.isValidExpression(expr.trim())
                ? "  ·  ✓ 表达式有效" : "  ·  ✗ 表达式非法";
    }

    /** 执行内容：提示词。 */
    private VBox buildContentSection(ScheduledTask task) {
        Label gt = new Label("执行内容");
        gt.getStyleClass().add("grp-title");
        Label lab = smallLabel("提示词 / 指令");
        Label hint = smallHint("任务触发时，以下内容将作为用户消息发送给智能体执行");
        promptArea = new TextArea(task.getPrompt() == null ? "" : task.getPrompt());
        promptArea.setWrapText(true);
        promptArea.setPrefRowCount(3);
        promptArea.getStyleClass().add("skill-content-editor");
        promptArea.setPromptText("描述要自动执行的任务…\n例如：检索今天的中文科技要闻 Top 8 并推送到钉钉");
        return new VBox(6, gt, lab, hint, promptArea);
    }

    /** 完成后通知：开关 + 渠道。 */
    private VBox buildNotifySection(ScheduledTask task) {
        Label gt = new Label("完成后通知");
        gt.getStyleClass().add("grp-title");

        Label main = new Label("运行完成后推送通知");
        main.getStyleClass().add("jc-stat-value");
        Label sub = new Label("选择渠道，任务每次结束时发送执行结果");
        sub.getStyleClass().add("sec-hint");
        VBox texts = new VBox(2, main, sub);
        HBox.setHgrow(texts, Priority.ALWAYS);

        notifyToggle = new ToggleSwitch(task.isNotifyEnabled());

        channelCombo = new ComboBox<>();
        for (String key : TaskNotificationChannel.ORDERED_CHANNELS) {
            channelCombo.getItems().add(TaskNotificationChannel.displayLabel(key));
        }
        channelCombo.setValue(TaskNotificationChannel.displayLabel(
                task.getNotifyChannel() == null ? "none" : task.getNotifyChannel()));
        channelCombo.setDisable(!task.isNotifyEnabled());
        notifyToggle.selectedProperty().addListener((o, a, b) -> channelCombo.setDisable(!b));

        HBox row = new HBox(12, texts, channelCombo, notifyToggle);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("jc-card-sunken");
        row.setPadding(new Insets(10, 12, 10, 12));
        return new VBox(6, gt, row);
    }

    /** 运行历史表。 */
    private VBox buildHistorySection(ScheduledTask task) {
        Label gt = new Label("运行历史");
        gt.getStyleClass().add("grp-title");
        historyBox = new VBox();
        historyBox.getStyleClass().add("jc-card");
        historyBox.setStyle("-fx-background-radius:10; -fx-border-radius:10;");
        refreshHistory(task);
        return new VBox(6, gt, historyBox);
    }

    private void refreshHistory(ScheduledTask task) {
        if (historyBox == null) return;
        historyBox.getChildren().clear();
        historyBox.getChildren().add(buildHistoryRow("时间", "状态", "耗时", "Token / 备注", true, "ok"));
        List<ScheduledTask.ExecRecord> records = task.getExecRecords();
        if (records == null || records.isEmpty()) {
            Label empty = new Label("暂无执行记录");
            empty.getStyleClass().add("sec-hint");
            empty.setPadding(new Insets(10, 13, 10, 13));
            historyBox.getChildren().add(empty);
            return;
        }
        for (ScheduledTask.ExecRecord r : records) {
            String stateKey = "失败".equals(r.getStatus()) ? "fail" : "ok";
            historyBox.getChildren().add(buildHistoryRow(
                    r.getTime(), r.getStatus(), r.getDuration(),
                    r.getNote() == null || r.getNote().isBlank() ? "—" : r.getNote(),
                    false, stateKey));
        }
    }

    private GridPane buildHistoryRow(String time, String status, String dur, String note,
                                     boolean head, String stateKey) {
        GridPane g = new GridPane();
        g.getStyleClass().add(head ? "jc-table-head" : "jc-table-row");
        ColumnConstraints c1 = new ColumnConstraints(); c1.setPercentWidth(38);
        ColumnConstraints c2 = new ColumnConstraints(); c2.setPercentWidth(18);
        ColumnConstraints c3 = new ColumnConstraints(); c3.setPercentWidth(18);
        ColumnConstraints c4 = new ColumnConstraints(); c4.setPercentWidth(26);
        g.getColumnConstraints().addAll(c1, c2, c3, c4);

        Label lt = new Label(time);
        Label ld = new Label(dur);
        Label ln = new Label(note);
        if (head) {
            for (Label l : List.of(lt, ld, ln)) l.getStyleClass().add("jc-table-head-cell");
            Label ls = new Label(status); ls.getStyleClass().add("jc-table-head-cell");
            g.add(lt, 0, 0); g.add(ls, 1, 0); g.add(ld, 2, 0); g.add(ln, 3, 0);
        } else {
            lt.getStyleClass().add("jc-table-cell");
            ld.getStyleClass().add("jc-table-cell-muted");
            ln.getStyleClass().add("fail".equals(stateKey) ? "jc-table-cell" : "jc-table-cell-muted");
            if ("fail".equals(stateKey)) ln.setStyle("-fx-text-fill:#EF4444;");
            ln.setWrapText(false);
            Label badge = new Label(status);
            badge.getStyleClass().addAll("jc-badge", "fail".equals(stateKey) ? "jc-badge-fail" : "jc-badge-ok");
            badge.setStyle("-fx-font-size:10px; -fx-padding:1 8 1 8;");
            g.add(lt, 0, 0); g.add(badge, 1, 0); g.add(ld, 2, 0); g.add(ln, 3, 0);
        }
        GridPane.setValignment(lt, javafx.geometry.VPos.CENTER);
        return g;
    }

    private HBox buildDeleteRow() {
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button del = new Button("删除任务");
        del.getStyleClass().addAll("jc-btn", "jc-btn-danger", "jc-btn-sm");
        del.setOnAction(e -> onDeleteTask());
        HBox row = new HBox(sp, del);
        row.setPadding(new Insets(8, 0, 4, 0));
        return row;
    }

    // ==================== 头部状态实时刷新 ====================

    private void refreshDetailStatus() {
        if (selectedTaskId == null || statTriggerVal == null) return;
        ScheduledTask t = scheduleManager.getTask(selectedTaskId);
        if (t == null) return;
        boolean running = scheduleManager.isRunning(t.getId());

        headerStateLabel.setText(running ? "运行中" : (t.isEnabled() ? "已启用" : "已暂停"));
        if (headerToggle.isSelected() != t.isEnabled()) headerToggle.setSelected(t.isEnabled());

        statTriggerVal.setText(t.describeTrigger());

        // 下次运行
        if (running) {
            statNextVal.setText("执行中…"); statNextSub.setText("");
        } else if (!t.isEnabled()) {
            statNextVal.setText("—"); statNextSub.setText("");
        } else {
            LocalDateTime n = scheduleManager.getNextFireTime(t.getId());
            statNextVal.setText(n == null ? "等待调度" : n.format(NEXT_FMT));
            statNextSub.setText(n == null ? "" : formatRelative(n));
        }

        // 上次运行
        boolean hasLast = t.getLastRunTime() != null && !t.getLastRunTime().isEmpty();
        statLastVal.setText(hasLast ? t.getLastRunTime() : "—");
        statLastBadge.getStyleClass().removeAll("jc-badge-ok", "jc-badge-fail");
        if (hasLast) {
            boolean ok = "成功".equals(t.getLastRunStatus());
            statLastBadge.setText(t.getLastRunStatus());
            statLastBadge.getStyleClass().add(ok ? "jc-badge-ok" : "jc-badge-fail");
            statLastBadge.setVisible(true);
            statLastBadge.setManaged(true);
        } else {
            statLastBadge.setVisible(false);
            statLastBadge.setManaged(false);
        }

        statRunsVal.setText(t.getRunCount() + " / " + t.getFailCount());
    }

    // ==================== 事件 ====================

    private void onCreateTask() {
        ScheduledTask t = scheduleManager.createTask("新定时任务");
        selectedTaskId = t.getId();
        refreshTaskList();
        showDetail(t);
        if (nameField != null) {
            nameField.requestFocus();
            nameField.selectAll();
        }
        if (statusHint != null) statusHint.setText("已创建，请填写触发规则与提示词后点「保存」");
    }

    /** 头部开关切换：立即启用/停用并持久化（不依赖「保存」）。 */
    private void onToggleEnabled(boolean enabled) {
        if (selectedTaskId == null) return;
        ScheduledTask t = scheduleManager.getTask(selectedTaskId);
        if (t == null || t.isEnabled() == enabled) return;
        readForm(t);                 // 先吸收当前编辑内容，避免开关丢编辑
        t.setEnabled(enabled);
        scheduleManager.updateTask(t);
        refreshTaskList();
        refreshDetailStatus();
        statusHint.setText(enabled ? "已启用" : "已暂停");
    }

    private void onSaveTask() {
        if (selectedTaskId == null) return;
        ScheduledTask t = scheduleManager.getTask(selectedTaskId);
        if (t == null) return;
        readForm(t);
        scheduleManager.updateTask(t);
        refreshTaskList();
        refreshDetailStatus();
        statusHint.setText("已保存");
    }

    /** 把当前编辑控件读回到 task。 */
    private void readForm(ScheduledTask t) {
        if (nameField != null && !nameField.getText().trim().isEmpty()) t.setName(nameField.getText().trim());
        if (promptArea != null) t.setPrompt(promptArea.getText());
        t.setTriggerType(currentTriggerType);
        switch (currentTriggerType) {
            case "once" -> {
                String d = onceDateField != null ? onceDateField.getText().trim() : "";
                String tm = onceTimeField != null ? onceTimeField.getText().trim() : "";
                t.setOnceDateTime((d + " " + tm).trim());
            }
            case "interval" -> {
                int v = 30;
                try { v = Integer.parseInt(intervalValueField.getText().trim()); } catch (Exception ignore) {}
                t.setIntervalValue(Math.max(1, v));
                t.setIntervalUnit(unitKey(intervalUnitCombo.getValue()));
                t.recomputeIntervalMinutes();
            }
            case "daily" -> t.setDailyTime(dailyTimeField != null ? dailyTimeField.getText().trim() : "09:00");
            case "cron" -> t.setCronExpression(cronField != null ? cronField.getText().trim() : "");
        }
        if (notifyToggle != null) {
            t.setNotifyEnabled(notifyToggle.isSelected());
            t.setNotifyChannel(channelCombo != null
                    ? TaskNotificationChannel.fromLabel(channelCombo.getValue()) : "none");
        }
    }

    private void onRunNow() {
        if (selectedTaskId == null) return;
        onSaveTask();
        scheduleManager.runNow(selectedTaskId);
        statusHint.setText("正在执行…");
    }

    private void onDeleteTask() {
        if (selectedTaskId == null) return;
        ScheduledTask t = scheduleManager.getTask(selectedTaskId);
        if (t == null) return;
        Alert alert = UIHelper.createConfirmAlert("确认删除",
                "确定要删除定时任务「" + t.getName() + "」吗？", stage);
        alert.showAndWait().ifPresent(r -> {
            if (r == ButtonType.OK) {
                scheduleManager.deleteTask(selectedTaskId);
                selectedTaskId = null;
                showEmpty();
                refreshTaskList();
            }
        });
    }

    private void showEmpty() {
        detailScroll.setVisible(false);
        detailScroll.setManaged(false);
        emptyPanel.setVisible(true);
        emptyPanel.setManaged(true);
        updateFootButtons();
    }

    private void updateFootButtons() {
        boolean has = selectedTaskId != null && scheduleManager.getTask(selectedTaskId) != null;
        if (runNowBtn != null) { runNowBtn.setDisable(!has); }
        if (saveBtn != null) { saveBtn.setDisable(!has); }
    }

    public void show() {
        refreshTaskList();
        if (selectedTaskId != null) {
            ScheduledTask t = scheduleManager.getTask(selectedTaskId);
            if (t != null) showDetail(t); else showEmpty();
        }
        startStatusTimer();
        stage.setOnHidden(e -> stopStatusTimer());
        stage.showAndWait();
    }

    private void startStatusTimer() {
        if (statusTimer == null) {
            statusTimer = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
                        if (selectedTaskId != null && detailScroll.isVisible()) refreshDetailStatus();
                    }));
            statusTimer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        }
        statusTimer.playFromStart();
    }

    private void stopStatusTimer() {
        if (statusTimer != null) statusTimer.stop();
    }

    // ==================== 小工具 ====================

    private TextField labeledField(String prompt, String value, int width) {
        TextField f = new TextField(value);
        f.setPromptText(prompt);
        f.getStyleClass().add("settings-field");
        f.setPrefWidth(width);
        return f;
    }

    private VBox wrap(javafx.scene.Node field, String label) {
        return new VBox(4, smallLabel(label), field);
    }

    private Label smallLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("jc-stat-label");
        return l;
    }

    private Label smallHint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sec-hint");
        return l;
    }

    private String unitLabel(String key) {
        return switch (key == null ? "minute" : key) {
            case "hour" -> "小时";
            case "day" -> "天";
            default -> "分钟";
        };
    }

    private String unitKey(String label) {
        return switch (label == null ? "分钟" : label) {
            case "小时" -> "hour";
            case "天" -> "day";
            default -> "minute";
        };
    }

    private String formatRelative(LocalDateTime next) {
        Duration d = Duration.between(LocalDateTime.now(), next);
        long secs = d.getSeconds();
        if (secs <= 0) return "即将运行";
        long days = secs / 86400; secs %= 86400;
        long hours = secs / 3600; secs %= 3600;
        long mins = secs / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return String.format("%dh %02dm", hours, mins);
        if (mins > 0) return mins + "m";
        return d.getSeconds() + "s";
    }

    private void applyStylesheets(Scene scene) {
        addCss(scene, "/css/chat.css");
        addCss(scene, "/css/controls.css");
        addCss(scene, "/css/schedule.css");
    }

    private void addCss(Scene scene, String path) {
        var url = getClass().getResource(path);
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }
}
