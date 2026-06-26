package com.javaclaw.ui.javafx.task;

import com.javaclaw.task.TaskNotificationChannel;
import com.javaclaw.task.sdd.run.SddManagedTask;
import com.javaclaw.task.sdd.run.SddTaskListener;
import com.javaclaw.task.sdd.run.SddTaskManager;
import com.javaclaw.task.sdd.run.SddTaskState;
import com.javaclaw.task.sdd.spec.Capability;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;
import com.javaclaw.task.sdd.spec.Scenario;
import com.javaclaw.task.sdd.spec.TaskItem;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SDD 托管任务视图 —— 按设计稿 {@code modal_tasks.jsx}（modernized 版）实现的现代化弹窗。
 *
 * <p>布局：左侧任务列表（计数摘要 + 新建 + 任务卡片），右侧详情分三层——
 * 固定头部（环形进度 Donut + 标题/状态徽标 + 描述 + 四联统计卡 + OpenSpec 六阶段
 * 流水线步进器 + 页签栏），中部页签内容（概览 / 验收场景 / 实现清单 / 实时日志
 * 时间线），底部页脚操作栏（按状态重建操作按钮 + 关闭）。新建任务为右侧内联表单
 * （替换详情区，不再弹独立对话框）。评审由 {@code PortReviewGate} 在运行中弹
 * confirm，故无需 bespoke gate 对话框。订阅 {@link SddTaskListener} 做增量刷新。</p>
 *
 * <p>视觉令牌与通用骨架类来自 {@code /css/chat.css}（modal-left-pane / modal-content-area /
 * modal-foot / jc-btn-* / jc-badge-*），本弹窗专属细化落在 {@code /css/sdd-task.css}
 * （在 chat.css 之后追加加载，不改 chat.css）。设计稿超前于后端的部分按现有数据
 * 优雅降级：左卡片右上角显示进度百分比（阶段名需读盘）、耗时由 createdAt/updatedAt
 * 折算，不造假数据。</p>
 *
 * @author JavaClaw
 */
public final class SddTaskView {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** OpenSpec 六阶段（与 SddOrchestrator 生命周期一致） */
    private static final String[] STAGES = {"提案", "规格", "设计", "拆解", "实现", "验收"};

    /** 实时日志时间线行数上限（超出从头部裁剪，防长任务内存膨胀） */
    private static final int MAX_LOG_ROWS = 400;

    /** 日志行格式 [HH:mm:ss] 文本 */
    private static final Pattern LOG_LINE = Pattern.compile("^\\[([^\\]]+)\\]\\s*(.*)$");

    private final Stage stage;
    private final SddTaskManager mgr = SddTaskManager.getInstance();

    // ---- 左侧列表 ----
    private final Label countLabel = new Label();
    private final VBox taskListBox = new VBox(6);

    // ---- 右侧三面板（空态 / 详情 / 内联新建） ----
    private final StackPane contentArea = new StackPane();
    private VBox emptyPanel;
    private VBox detailPanel;
    private final VBox createPanel = new VBox();

    // ---- 详情头部：Donut 环形进度 ----
    private final Arc donutArc = new Arc();
    private final Label donutPct = new Label("0");

    private final Label titleLabel = new Label();
    private final Label descLabel = new Label();
    private final Label stateBadge = new Label();

    // ---- 四联统计卡 ----
    private final Label stageTileValue = new Label();
    private final Label checklistTileValue = new Label();
    private final Label tokenTileValue = new Label();
    private final ProgressBar tokenTileBar = new ProgressBar(0);
    private final Label tokenTileHint = new Label();
    private final Label elapsedTileValue = new Label();
    private final Label elapsedTileHint = new Label();

    // ---- 六阶段流水线步进器 ----
    private final Label[] pipeDots = new Label[STAGES.length];
    private final Label[] pipeNames = new Label[STAGES.length];
    private final Region[] pipeLines = new Region[STAGES.length - 1];

    // ---- 页签 ----
    private final Map<String, Button> tabButtons = new LinkedHashMap<>();
    private final VBox overviewPane = new VBox(14);
    private final VBox acceptPane = new VBox(12);
    private final VBox checklistPane = new VBox(0);
    private final VBox logPane = new VBox(2);
    private String activeTab = "overview";

    // ---- 页脚操作栏（按状态重建） ----
    private final HBox actionBox = new HBox(8);

    private SddManagedTask selected;

    /** 内联新建表单展示中：后台任务更新不得把表单顶掉（showDetail 据此跳过面板切换） */
    private boolean creating = false;

    public SddTaskView(Stage owner) {
        this.stage = new Stage();
        this.stage.initOwner(owner);
        this.stage.initModality(Modality.WINDOW_MODAL);
        this.stage.setTitle("托管任务");
        Scene scene = new Scene(buildRoot(), 1000, 700);
        applyStylesheet(scene);
        // Esc 关闭窗口（与页脚「关闭」按钮同路径）
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.ESCAPE), stage::close);
        this.stage.setScene(scene);
        registerListener();
    }

    public void show() {
        refreshList();
        stage.show();
        stage.toFront();
    }

    public void showCreate() {
        show();
        openCreateForm(null);
    }

    /** 打开并预填需求描述（由 /任务 命令触发）。 */
    public void showCreate(String description) {
        show();
        openCreateForm(description);
    }

    public void show(String taskId) {
        show();
        mgr.list().stream().filter(t -> t.id.equals(taskId)).findFirst().ifPresent(this::select);
    }

    // ==================== 布局 ====================

    private HBox buildRoot() {
        HBox root = new HBox();
        root.getStyleClass().add("settings-root");
        root.getChildren().addAll(buildLeftPane(), buildRightPane());
        return root;
    }

    private VBox buildLeftPane() {
        Label glyph = new Label("▦");
        glyph.getStyleClass().add("sdd-left-glyph");
        Label navTitle = new Label("托管任务");
        navTitle.getStyleClass().add("modal-left-title");
        HBox titleRow = new HBox(8, glyph, navTitle);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        countLabel.getStyleClass().add("sdd-left-count");

        VBox head = new VBox(6, titleRow, countLabel);
        head.getStyleClass().add("sdd-left-head");

        Button addBtn = new Button("＋ 新建任务");
        addBtn.getStyleClass().addAll("jc-btn", "jc-btn-primary", "jc-btn-sm");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setOnAction(e -> openCreateForm(null));
        HBox addRow = new HBox(addBtn);
        addRow.getStyleClass().add("sdd-add-row");
        HBox.setHgrow(addBtn, Priority.ALWAYS);

        taskListBox.getStyleClass().add("sdd-list-box");

        ScrollPane listScroll = new ScrollPane(taskListBox);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.getStyleClass().add("settings-scroll-pane");
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        VBox left = new VBox(head, addRow, listScroll);
        left.getStyleClass().addAll("modal-left-pane", "sdd-left-pane");
        return left;
    }

    private VBox buildRightPane() {
        contentArea.getStyleClass().add("modal-content-area");

        emptyPanel = buildEmptyPanel();
        detailPanel = buildDetailPanel();
        buildCreatePanelShell();
        contentArea.getChildren().addAll(emptyPanel, detailPanel, createPanel);
        showPanel(emptyPanel);

        actionBox.setAlignment(Pos.CENTER_LEFT);
        Button closeBtn = new Button("关闭");
        closeBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost");
        closeBtn.setOnAction(e -> stage.close());
        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(8, actionBox, footSpacer, closeBtn);
        bottomBar.setAlignment(Pos.CENTER_RIGHT);
        bottomBar.getStyleClass().add("modal-foot");

        VBox right = new VBox(contentArea, bottomBar);
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        return right;
    }

    private VBox buildEmptyPanel() {
        Label icon = new Label("▦");
        icon.getStyleClass().add("empty-state-icon");
        Label text = new Label("选择或新建一个托管任务");
        text.getStyleClass().add("empty-state-text");
        Label hint = new Label(
                "托管任务以 OpenSpec 流程推进：提案 → 规格 → 设计 → 任务拆解 → 实现 → 验收。\n"
                + "进度即 tasks.md 勾选折叠，验收由场景核验单点把关。");
        hint.getStyleClass().addAll("settings-hint", "empty-state-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(380);
        VBox panel = new VBox(12, icon, text, hint);
        panel.setAlignment(Pos.CENTER);
        return panel;
    }

    // ==================== 详情面板 ====================

    private VBox buildDetailPanel() {
        // ---- 头部固定区：Donut + 标题/徽标 + 描述 + 统计卡 ----
        StackPane donut = buildDonut();

        titleLabel.getStyleClass().add("sdd-header-title");
        titleLabel.setWrapText(true);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        stateBadge.getStyleClass().add("jc-badge");
        HBox titleRow = new HBox(10, titleLabel, stateBadge);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        descLabel.getStyleClass().add("sdd-header-desc");
        descLabel.setWrapText(true);

        HBox statsRow = new HBox(8,
                buildStatTile("阶段", stageTileValue),
                buildStatTile("实现清单", checklistTileValue),
                buildStatTile("Token", tokenTileValue, tokenTileBar, tokenTileHint),
                buildStatTile("耗时", elapsedTileValue, elapsedTileHint));
        tokenTileBar.getStyleClass().add("sdd-stat-bar");
        tokenTileBar.setMaxWidth(Double.MAX_VALUE);

        VBox headText = new VBox(6, titleRow, descLabel, statsRow);
        VBox.setMargin(statsRow, new Insets(6, 0, 0, 0));
        HBox.setHgrow(headText, Priority.ALWAYS);
        HBox headRow = new HBox(14, donut, headText);
        headRow.setAlignment(Pos.TOP_LEFT);

        // ---- OpenSpec 流水线步进器 ----
        HBox pipeline = buildPipeline();

        // ---- 页签栏 ----
        HBox tabBar = new HBox(2);
        tabBar.getStyleClass().add("sdd-tab-bar");
        tabBar.getChildren().addAll(
                buildTab("overview", "概览"),
                buildTab("accept", "验收场景"),
                buildTab("checklist", "实现清单"),
                buildTab("log", "实时日志"));

        VBox headerZone = new VBox(0, headRow, pipeline, tabBar);
        headerZone.getStyleClass().add("sdd-head-zone");
        VBox.setMargin(pipeline, new Insets(16, 0, 12, 0));

        // ---- 页签内容（滚动区） ----
        overviewPane.getStyleClass().add("sdd-tab-pane");
        acceptPane.getStyleClass().add("sdd-tab-pane");
        checklistPane.getStyleClass().add("sdd-tab-pane");
        logPane.getStyleClass().addAll("sdd-tab-pane", "sdd-log-timeline");
        StackPane tabContent = new StackPane(overviewPane, acceptPane, checklistPane, logPane);
        tabContent.setAlignment(Pos.TOP_LEFT);

        ScrollPane scroll = new ScrollPane(tabContent);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("settings-scroll-pane", "sdd-detail-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox panel = new VBox(headerZone, scroll);
        applyTab("overview");
        return panel;
    }

    /** 环形进度（76px，7px 圆头描边），中心叠加百分比数字。 */
    private StackPane buildDonut() {
        double size = 76, stroke = 7;
        double r = (size - stroke) / 2;

        Circle track = new Circle(size / 2, size / 2, r);
        track.getStyleClass().add("sdd-donut-track");
        track.setStrokeWidth(stroke);

        donutArc.setCenterX(size / 2);
        donutArc.setCenterY(size / 2);
        donutArc.setRadiusX(r);
        donutArc.setRadiusY(r);
        donutArc.setStartAngle(90);
        donutArc.setLength(0);
        donutArc.setType(ArcType.OPEN);
        donutArc.setStrokeWidth(stroke);
        donutArc.setStrokeLineCap(StrokeLineCap.ROUND);
        donutArc.getStyleClass().add("sdd-donut-arc");

        Pane shapes = new Pane(track, donutArc);
        shapes.setMinSize(size, size);
        shapes.setPrefSize(size, size);
        shapes.setMaxSize(size, size);

        donutPct.getStyleClass().add("sdd-donut-pct");
        Label pctSign = new Label("%");
        pctSign.getStyleClass().add("sdd-donut-sign");
        VBox center = new VBox(donutPct, pctSign);
        center.setAlignment(Pos.CENTER);
        center.setSpacing(-2);

        StackPane donut = new StackPane(shapes, center);
        donut.setMinSize(size, size);
        donut.setMaxSize(size, size);
        return donut;
    }

    /** 统计卡：标签 + mono 值 + 可选子节点（迷你条/提示行）。 */
    private VBox buildStatTile(String label, Label valueLabel, Node... subs) {
        Label l = new Label(label);
        l.getStyleClass().add("sdd-stat-label");
        valueLabel.getStyleClass().add("sdd-stat-value");
        VBox tile = new VBox(4, l, valueLabel);
        for (Node sub : subs) {
            tile.getChildren().add(sub);
        }
        tile.getStyleClass().add("sdd-stat-tile");
        HBox.setHgrow(tile, Priority.ALWAYS);
        return tile;
    }

    /** 六阶段流水线：圆点（序号/✓/‖）+ 阶段名 + 连接线。 */
    private HBox buildPipeline() {
        HBox pipeline = new HBox();
        pipeline.setAlignment(Pos.TOP_LEFT);
        pipeline.getStyleClass().add("sdd-pipeline");
        for (int i = 0; i < STAGES.length; i++) {
            Label dot = new Label(String.valueOf(i + 1));
            dot.getStyleClass().addAll("sdd-pipe-dot", "sdd-pipe-dot-pending");
            Label name = new Label(STAGES[i]);
            name.getStyleClass().addAll("sdd-pipe-name", "sdd-pipe-name-pending");
            pipeDots[i] = dot;
            pipeNames[i] = name;
            VBox step = new VBox(6, dot, name);
            step.setAlignment(Pos.TOP_CENTER);
            step.setMinWidth(48);
            pipeline.getChildren().add(step);
            if (i < STAGES.length - 1) {
                Region line = new Region();
                line.getStyleClass().add("sdd-pipe-line");
                line.setTranslateY(12);
                HBox.setHgrow(line, Priority.ALWAYS);
                pipeLines[i] = line;
                pipeline.getChildren().add(line);
            }
        }
        return pipeline;
    }

    private Button buildTab(String key, String label) {
        Button b = new Button(label);
        b.getStyleClass().add("sdd-tab");
        b.setOnAction(e -> applyTab(key));
        tabButtons.put(key, b);
        return b;
    }

    private void applyTab(String key) {
        activeTab = key;
        tabButtons.forEach((k, b) -> {
            b.getStyleClass().remove("sdd-tab-active");
            if (k.equals(key)) b.getStyleClass().add("sdd-tab-active");
        });
        setPaneVisible(overviewPane, "overview".equals(key));
        setPaneVisible(acceptPane, "accept".equals(key));
        setPaneVisible(checklistPane, "checklist".equals(key));
        setPaneVisible(logPane, "log".equals(key));
    }

    private static void setPaneVisible(Node pane, boolean visible) {
        pane.setVisible(visible);
        pane.setManaged(visible);
    }

    private void showPanel(Node panel) {
        for (Node p : contentArea.getChildren()) {
            setPaneVisible(p, p == panel);
        }
        // 非详情面板不显示状态操作按钮
        if (panel != detailPanel) {
            actionBox.getChildren().clear();
        }
    }

    // ==================== 选择 / 渲染 ====================

    private void select(SddManagedTask t) {
        creating = false;
        boolean switched = selected == null || !selected.id.equals(t.id);
        this.selected = t;
        refreshList();
        if (switched) {
            logPane.getChildren().clear();
            applyTab("overview");
        }
        showDetail(t);
    }

    private void showDetail(SddManagedTask t) {
        if (creating) {
            return;
        }
        if (t == null) {
            showPanel(emptyPanel);
            return;
        }
        showPanel(detailPanel);

        Optional<OpenSpecChange> chOpt = mgr.readChange(t.id);
        OpenSpecChange ch = chOpt.orElse(null);

        titleLabel.setText(t.title);
        descLabel.setText(blankToDash(t.description) + "　·　工作目录 " + blankToDash(t.workDir));
        applyBadge(stateBadge, t.state);

        // Donut：进度 + 待人工配色
        donutPct.setText(String.valueOf(t.progress));
        donutArc.setLength(-360.0 * t.progress / 100.0);
        donutArc.getStyleClass().remove("sdd-donut-arc-amber");
        if (t.state == SddTaskState.NEEDS_HUMAN) {
            donutArc.getStyleClass().add("sdd-donut-arc-amber");
        }

        int stageIdx = stageIndexOf(t, ch);
        renderStats(t, ch, stageIdx);
        renderPipeline(stageIdx, t.state);
        renderOverview(t, ch);
        renderAccept(t, ch);
        renderChecklist(ch);
        rebuildActions(t);
    }

    /** 由 change 内容 + 管理器状态派生当前 OpenSpec 阶段索引（0–5，6=全部完成）。 */
    private int stageIndexOf(SddManagedTask t, OpenSpecChange ch) {
        if (t.state == SddTaskState.COMPLETED) return STAGES.length;
        if (ch == null || ch.proposal() == null) return 0;
        if (ch.capabilities().isEmpty()) return 1;
        if (ch.tasks().isEmpty()) {
            return (ch.design() == null || ch.design().isBlank()) ? 2 : 3;
        }
        return ch.allTasksDone() ? 5 : 4;
    }

    private void renderStats(SddManagedTask t, OpenSpecChange ch, int stageIdx) {
        // 阶段卡（按状态着色）
        stageTileValue.setText(t.state == SddTaskState.COMPLETED
                ? "已完成" : STAGES[Math.min(stageIdx, STAGES.length - 1)]);
        stageTileValue.getStyleClass().removeAll(
                "sdd-stat-value-brand", "sdd-stat-value-amber", "sdd-stat-value-danger");
        switch (t.state) {
            case RUNNING, COMPLETED -> stageTileValue.getStyleClass().add("sdd-stat-value-brand");
            case NEEDS_HUMAN -> stageTileValue.getStyleClass().add("sdd-stat-value-amber");
            case FAILED -> stageTileValue.getStyleClass().add("sdd-stat-value-danger");
            default -> { }
        }

        // 实现清单卡
        if (ch == null || ch.tasks().isEmpty()) {
            checklistTileValue.setText("—");
        } else {
            long done = ch.tasks().stream().filter(TaskItem::done).count();
            checklistTileValue.setText(done + "/" + ch.tasks().size());
        }

        // Token 卡：已用量 + 预算迷你条
        long used = t.totalInputTokens + t.totalOutputTokens;
        tokenTileValue.setText(fmtTokens(used));
        if (t.tokenBudget > 0) {
            double pct = Math.min(1.0, used / (double) t.tokenBudget);
            tokenTileBar.setProgress(pct);
            tokenTileBar.getStyleClass().remove("sdd-stat-bar-danger");
            if (pct > 0.85) tokenTileBar.getStyleClass().add("sdd-stat-bar-danger");
            setPaneVisible(tokenTileBar, true);
            tokenTileHint.setText("预算 " + fmtTokens(t.tokenBudget));
        } else {
            setPaneVisible(tokenTileBar, false);
            tokenTileHint.setText("预算不限");
        }
        tokenTileHint.getStyleClass().setAll("sdd-stat-hint");

        // 耗时卡（createdAt → updatedAt/now 诚实折算）
        String[] elapsed = elapsedOf(t);
        elapsedTileValue.setText(elapsed[0]);
        elapsedTileHint.setText(elapsed[1]);
        elapsedTileHint.getStyleClass().setAll("sdd-stat-hint");
    }

    /** 流水线步进器状态刷新：done=品牌实心✓ / active=描边高亮（待人工为琥珀‖）/ pending=灰。 */
    private void renderPipeline(int stageIdx, SddTaskState state) {
        boolean paused = state == SddTaskState.NEEDS_HUMAN || state == SddTaskState.PAUSED;
        for (int i = 0; i < STAGES.length; i++) {
            boolean done = i < stageIdx;
            boolean active = i == stageIdx && state != SddTaskState.COMPLETED;
            pipeDots[i].getStyleClass().removeAll(
                    "sdd-pipe-dot-done", "sdd-pipe-dot-active", "sdd-pipe-dot-amber", "sdd-pipe-dot-pending");
            pipeNames[i].getStyleClass().removeAll(
                    "sdd-pipe-name-done", "sdd-pipe-name-active", "sdd-pipe-name-pending");
            if (done) {
                pipeDots[i].setText("✓");
                pipeDots[i].getStyleClass().add("sdd-pipe-dot-done");
                pipeNames[i].getStyleClass().add("sdd-pipe-name-done");
            } else if (active) {
                pipeDots[i].setText(paused ? "‖" : String.valueOf(i + 1));
                pipeDots[i].getStyleClass().add(paused ? "sdd-pipe-dot-amber" : "sdd-pipe-dot-active");
                pipeNames[i].getStyleClass().add("sdd-pipe-name-active");
            } else {
                pipeDots[i].setText(String.valueOf(i + 1));
                pipeDots[i].getStyleClass().add("sdd-pipe-dot-pending");
                pipeNames[i].getStyleClass().add("sdd-pipe-name-pending");
            }
        }
        for (int i = 0; i < pipeLines.length; i++) {
            pipeLines[i].getStyleClass().remove("sdd-pipe-line-done");
            if (i < stageIdx) pipeLines[i].getStyleClass().add("sdd-pipe-line-done");
        }
    }

    // ==================== 页签内容 ====================

    /** 概览：结果卡（如有）+ why 卡 + whatChanges 编号卡列 + 不做 + 真相路径。 */
    private void renderOverview(SddManagedTask t, OpenSpecChange ch) {
        overviewPane.getChildren().clear();

        // 终态/待人工结果说明置顶
        if (t.result != null && !t.result.isBlank()) {
            boolean failed = t.state == SddTaskState.FAILED;
            Label rt = new Label(failed ? "任务失败" : t.state == SddTaskState.COMPLETED ? "任务结果" : "等待人工处理");
            rt.getStyleClass().add("sdd-result-title");
            if (failed) rt.getStyleClass().add("sdd-result-title-failed");
            Label rc = new Label(t.result);
            rc.getStyleClass().add("sdd-result-content");
            rc.setWrapText(true);
            VBox resultCard = new VBox(4, rt, rc);
            resultCard.getStyleClass().add("sdd-result-card");
            if (failed) resultCard.getStyleClass().add("sdd-result-card-failed");
            overviewPane.getChildren().add(resultCard);
        }

        if (ch == null || ch.proposal() == null) {
            overviewPane.getChildren().add(mutedLine(
                    "（尚无 change 产物：任务未开始或工作目录未生成 .agent/openspec）"));
            return;
        }
        Proposal p = ch.proposal();

        // why 卡
        Label whyBody = new Label(blankToDash(p.why()));
        whyBody.getStyleClass().add("sdd-body-text");
        whyBody.setWrapText(true);
        VBox whyCard = new VBox(8, chipTitle("?", "为什么做（why）"), whyBody);
        whyCard.getStyleClass().add("sdd-why-card");
        overviewPane.getChildren().add(whyCard);

        // whatChanges 编号卡列
        VBox changes = new VBox(8, chipTitle("Δ", "变更内容（whatChanges）"));
        List<String> items = splitLines(p.whatChanges());
        if (items.isEmpty()) {
            changes.getChildren().add(mutedLine("—"));
        } else {
            int n = 1;
            for (String item : items) {
                Label num = new Label(String.format("%02d", n++));
                num.getStyleClass().add("sdd-change-num");
                Label text = new Label(item);
                text.getStyleClass().add("sdd-body-text");
                text.setWrapText(true);
                HBox.setHgrow(text, Priority.ALWAYS);
                HBox row = new HBox(11, num, text);
                row.setAlignment(Pos.TOP_LEFT);
                row.getStyleClass().add("sdd-change-item");
                changes.getChildren().add(row);
            }
        }
        overviewPane.getChildren().add(changes);

        // 不做（outOfScope）
        if (p.outOfScope() != null && !p.outOfScope().isBlank()) {
            Label osBody = new Label(p.outOfScope());
            osBody.getStyleClass().add("sdd-body-text");
            osBody.setWrapText(true);
            VBox osCard = new VBox(8, chipTitle("✕", "不做（outOfScope）"), osBody);
            osCard.getStyleClass().add("sdd-why-card");
            overviewPane.getChildren().add(osCard);
        }

        Label path = new Label("markdown 即真相 · .agent/openspec/changes/" + ch.slug() + "/");
        path.getStyleClass().add("sdd-truth-path");
        overviewPane.getChildren().add(path);
    }

    /** 验收场景：每场景一卡（panel 头行：能力名 + 通过/待核验徽标；体：G/W/T 着色键行）。 */
    private void renderAccept(SddManagedTask t, OpenSpecChange ch) {
        acceptPane.getChildren().clear();
        int count = 0;
        if (ch != null) {
            boolean passed = t.state == SddTaskState.COMPLETED;
            for (Capability cap : ch.capabilities()) {
                for (Scenario s : cap.allScenarios()) {
                    acceptPane.getChildren().add(scenarioCard(cap.name(), s, passed));
                    count++;
                }
            }
        }
        if (count == 0) {
            acceptPane.getChildren().add(mutedLine("（尚未生成验收场景）"));
        }
        tabButtons.get("accept").setText(count == 0 ? "验收场景" : "验收场景 " + count);
    }

    /** 实现清单：tasks.md 进度行 + 勾选项（首个未完成项高亮「进行中」）。 */
    private void renderChecklist(OpenSpecChange ch) {
        checklistPane.getChildren().clear();
        if (ch == null || ch.tasks().isEmpty()) {
            checklistPane.getChildren().add(mutedLine("（尚未拆解任务）"));
            return;
        }
        long done = ch.tasks().stream().filter(TaskItem::done).count();

        Label mdLabel = new Label("tasks.md");
        mdLabel.getStyleClass().add("sdd-checklist-md");
        ProgressBar bar = new ProgressBar(done / (double) ch.tasks().size());
        bar.getStyleClass().add("sdd-checklist-bar");
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        Label counter = new Label(done + "/" + ch.tasks().size());
        counter.getStyleClass().add("sdd-checklist-counter");
        HBox progressRow = new HBox(10, mdLabel, bar, counter);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        progressRow.setPadding(new Insets(0, 0, 12, 0));
        checklistPane.getChildren().add(progressRow);

        boolean activeFound = false;
        VBox items = new VBox(3);
        for (TaskItem item : ch.tasks()) {
            boolean active = !item.done() && !activeFound;
            if (active) activeFound = true;
            items.getChildren().add(checkRow(item, active));
        }
        checklistPane.getChildren().add(items);
    }

    /** 日志时间线追加一行：解析 [时间] 前缀与 ✓/⚠/⚙ 语义点色。 */
    private void appendLogRow(String message) {
        Matcher m = LOG_LINE.matcher(message);
        String time = m.matches() ? m.group(1) : "";
        String text = m.matches() ? m.group(2) : message;

        String dotClass = "sdd-log-dot-hint";
        if (text.contains("✓")) dotClass = "sdd-log-dot-ok";
        else if (text.contains("⚠")) dotClass = "sdd-log-dot-warn";
        else if (text.contains("⚙")) dotClass = "sdd-log-dot-info";

        Region dot = new Region();
        dot.getStyleClass().addAll("sdd-log-dot", dotClass);
        Label timeLabel = new Label(time);
        timeLabel.getStyleClass().add("sdd-log-time");
        Label textLabel = new Label(text);
        textLabel.getStyleClass().add("sdd-log-text");
        textLabel.setWrapText(true);
        HBox.setHgrow(textLabel, Priority.ALWAYS);

        HBox row = new HBox(12, dot, timeLabel, textLabel);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("sdd-log-row");
        logPane.getChildren().add(row);
        if (logPane.getChildren().size() > MAX_LOG_ROWS) {
            logPane.getChildren().remove(0);
        }
    }

    // ==================== 页脚操作栏 ====================

    /** 按状态重建页脚操作（设计稿：状态语义文案 + 状态相关按钮）。 */
    private void rebuildActions(SddManagedTask t) {
        actionBox.getChildren().clear();
        String stamp = LocalDateTime.now().format(TS);
        switch (t.state) {
            case PENDING -> actionBox.getChildren().addAll(
                    primary("▶ 启动", () -> mgr.start(t.id, stamp)),
                    cancel("🗑 删除", () -> deleteTask(t)));
            case RUNNING -> actionBox.getChildren().addAll(
                    secondary("改预算", () -> openBudgetDialog(t, null)),
                    secondary("⏸ 暂停", () -> mgr.pause(t.id)),
                    cancel("⏹ 取消", () -> mgr.cancel(t.id)));
            case NEEDS_HUMAN -> actionBox.getChildren().addAll(
                    footStatus("⚠ " + shortReason(t), false),
                    save("提升预算并续跑", () -> openBudgetDialog(t,
                            // 仅当预算被实际修改并保存时才续跑；取消对话框不触发
                            () -> mgr.resume(t.id, LocalDateTime.now().format(TS)))),
                    secondary("▶ 继续", () -> mgr.resume(t.id, stamp)),
                    cancel("🗑 删除", () -> deleteTask(t)));
            case PAUSED -> actionBox.getChildren().addAll(
                    primary("▶ 继续", () -> mgr.resume(t.id, stamp)),
                    secondary("改预算", () -> openBudgetDialog(t, null)),
                    secondary("⏹ 取消", () -> mgr.cancel(t.id)),
                    cancel("🗑 删除", () -> deleteTask(t)));
            case COMPLETED -> actionBox.getChildren().addAll(
                    footStatus("✓ 全部场景通过", true),
                    secondary("↻ 重跑", () -> mgr.resume(t.id, stamp)),
                    cancel("🗑 删除", () -> deleteTask(t)));
            case FAILED, CANCELLED -> actionBox.getChildren().addAll(
                    secondary("↻ 重跑", () -> mgr.resume(t.id, stamp)),
                    cancel("🗑 删除", () -> deleteTask(t)));
        }
    }

    /** 页脚状态语义文案（完成绿 / 告警琥珀）。 */
    private Label footStatus(String text, boolean ok) {
        Label l = new Label(text);
        l.getStyleClass().add(ok ? "sdd-foot-status-ok" : "sdd-foot-status-warn");
        l.setMaxWidth(320);
        return l;
    }

    /** 待人工原因短文案（取 result 首行，过长截断）。 */
    private String shortReason(SddManagedTask t) {
        if (t.result == null || t.result.isBlank()) return "需要人工介入";
        String first = t.result.lines().findFirst().orElse(t.result).trim();
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }

    /**
     * 修改 token 预算对话框：超限停为「待人工」后，调高预算再点「继续」即可续跑。
     *
     * @param onSaved 预算被实际修改并保存成功后的回调（可空）；仅当新预算与原预算不同时触发，
     *                取消对话框或填入相同值都不触发，供「提升预算并续跑」按钮接力 resume
     */
    private void openBudgetDialog(SddManagedTask t, Runnable onSaved) {
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("修改 Token 预算");

        Label dlgTitle = new Label("修改 Token 预算");
        dlgTitle.getStyleClass().add("task-dialog-title");
        Label hint = new Label("已用 " + (t.totalInputTokens + t.totalOutputTokens)
                + "（⬆ " + t.totalInputTokens + " + ⬇ " + t.totalOutputTokens + "）。"
                + "任务级累计用量达到预算时会停为「待人工」；调高预算后点「继续」续跑。填 0 表示不限制。");
        hint.getStyleClass().add("task-dialog-hint");
        hint.setWrapText(true);

        TextField budgetField = dialogField("1000000");
        budgetField.setText(String.valueOf(t.tokenBudget));

        Button ok = new Button("保存");
        ok.getStyleClass().add("task-dialog-primary-btn");
        ok.setOnAction(e -> {
            try {
                long newBudget = Math.max(0, Long.parseLong(safe(budgetField.getText())));
                boolean changed = newBudget != t.tokenBudget;
                mgr.updateTokenBudget(t.id, newBudget);
                dlg.close();
                // 仅当预算被实际修改才接力续跑；填入相同值视作未变更，不续跑
                if (changed && onSaved != null) onSaved.run();
            } catch (NumberFormatException ex) {
                budgetField.requestFocus();
            }
        });
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("task-dialog-ghost-btn");
        cancelBtn.setOnAction(e -> dlg.close());
        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);
        HBox btnRow = new HBox(8, btnSpacer, cancelBtn, ok);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(10, dlgTitle, hint,
                fieldLabel("Token 预算", true), budgetField,
                btnRow);
        box.getStyleClass().add("task-dialog-root");
        box.setPadding(new Insets(20, 22, 18, 22));

        Scene scene = new Scene(box, 440, 240);
        applyStylesheet(scene);
        dlg.setScene(scene);
        dlg.show();
        budgetField.requestFocus();
    }

    private void deleteTask(SddManagedTask t) {
        mgr.delete(t.id);
        selected = null;
        refreshList();
        showDetail(null);
    }

    // ==================== 左侧列表 ====================

    private void refreshList() {
        var tasks = mgr.list();
        long running = tasks.stream().filter(t -> t.state == SddTaskState.RUNNING).count();
        long human = tasks.stream().filter(t -> t.state == SddTaskState.NEEDS_HUMAN).count();
        countLabel.setText(running + " 运行中 · " + human + " 待人工 · 共 " + tasks.size());

        taskListBox.getChildren().clear();
        if (tasks.isEmpty()) {
            Label empty = new Label("暂无托管任务");
            empty.getStyleClass().add("settings-hint");
            empty.setPadding(new Insets(20, 0, 0, 4));
            taskListBox.getChildren().add(empty);
            return;
        }
        for (SddManagedTask t : tasks) {
            taskListBox.getChildren().add(buildTaskCard(t));
        }
    }

    private VBox buildTaskCard(SddManagedTask t) {
        // 首行：状态徽标（小号）+ 右侧 mono 进度百分比（阶段名需读盘，按现有数据降级）
        Label badge = new Label();
        applyCardBadge(badge, t.state);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label pct = new Label(t.progress + "%");
        pct.getStyleClass().add("sdd-card-pct");
        HBox metaRow = new HBox(6, badge, spacer, pct);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        // 任务标题（小字粗体，最多两行截断）
        Label title = new Label(t.title);
        title.getStyleClass().add("sdd-card-title");
        title.setMaxWidth(Double.MAX_VALUE);

        // 底部细进度条；待人工状态条色用阶段语义色
        ProgressBar bar = new ProgressBar(t.progress / 100.0);
        bar.getStyleClass().add("sdd-card-bar");
        if (t.state == SddTaskState.NEEDS_HUMAN) bar.getStyleClass().add("sdd-card-bar-amber");
        bar.setMaxWidth(Double.MAX_VALUE);

        VBox card = new VBox(7, metaRow, title, bar);
        card.getStyleClass().add("sdd-task-card");
        if (t.state.isTerminal()) card.getStyleClass().add("sdd-task-card-terminal");
        if (selected != null && selected.id.equals(t.id)) {
            card.getStyleClass().add("sdd-task-card-selected");
        }
        card.setOnMouseClicked(e -> select(t));
        return card;
    }

    // ==================== 内联新建表单 ====================

    private void buildCreatePanelShell() {
        createPanel.getStyleClass().add("sdd-create-panel");
    }

    /** 内联新建表单（设计稿：替换右侧详情区，不再弹独立对话框）。 */
    private void openCreateForm(String prefillDescription) {
        creating = true;
        createPanel.getChildren().clear();

        Label glyph = new Label("✦");
        glyph.getStyleClass().add("sdd-create-glyph");
        Label dlgTitle = new Label("新建托管任务");
        dlgTitle.getStyleClass().add("sdd-create-title");
        HBox titleRow = new HBox(8, glyph, dlgTitle);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label dlgSub = new Label("描述目标即可。托管任务以 OpenSpec 流程推进：提案 → 规格 → 设计 → "
                + "任务拆解 → 实现 → 验收，进度即 tasks.md 勾选折叠。");
        dlgSub.getStyleClass().add("sec-hint");
        dlgSub.setWrapText(true);

        TextField titleField = dialogField("留空将由描述自动生成标题");
        TextArea descArea = new TextArea();
        descArea.setPromptText("例如：为执行轨迹导出增加 CSV 格式，覆盖任务轨迹与 token 用量两类数据。");
        descArea.getStyleClass().add("task-dialog-textarea");
        descArea.setWrapText(true);
        descArea.setPrefRowCount(5);
        if (prefillDescription != null && !prefillDescription.isBlank()) descArea.setText(prefillDescription);

        TextField dirField = dialogField("留空默认在程序目录 task/ 下新建");
        HBox.setHgrow(dirField, Priority.ALWAYS);
        Button pick = new Button("选择…");
        pick.getStyleClass().add("task-dialog-browse-btn");
        pick.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            File f = dc.showDialog(stage);
            if (f != null) dirField.setText(f.getAbsolutePath());
        });
        HBox dirRow = new HBox(8, dirField, pick);
        dirRow.setAlignment(Pos.CENTER_LEFT);

        // ---- Token 预算：三档预设 + 自定义 ----
        // 档位文案 → 预算值；"自定义…" 由哨兵 -1 标记，选中时显示原 TextField
        final long CUSTOM_BUDGET = -1L;
        ComboBox<String> budgetBox = new ComboBox<>();
        budgetBox.getStyleClass().add("settings-combo");
        budgetBox.setMaxWidth(Double.MAX_VALUE);
        LinkedHashMap<String, Long> budgetPresets = new LinkedHashMap<>();
        budgetPresets.put("120K（推荐）", 120_000L);
        budgetPresets.put("80K", 80_000L);
        budgetPresets.put("200K", 200_000L);
        budgetPresets.put("不限", 0L);
        budgetPresets.put("自定义…", CUSTOM_BUDGET);
        budgetBox.getItems().addAll(budgetPresets.keySet());
        budgetBox.setValue("120K（推荐）");
        TextField budgetField = dialogField("自定义预算，如 1000000；0 表示不限");
        budgetField.setText("120000");
        budgetField.setVisible(false);
        budgetField.setManaged(false);
        budgetBox.valueProperty().addListener((obs, o, v) -> {
            boolean custom = budgetPresets.getOrDefault(v, 0L) == CUSTOM_BUDGET;
            budgetField.setVisible(custom);
            budgetField.setManaged(custom);
        });

        // ---- 能力工具集：自动路由 / 全量加载 / 自定义 ----
        // "auto" → ToolRouter 按需裁剪；"all" → 哨兵跳过路由直接全量装配；自定义 → 逗号分隔能力名
        final String CAP_AUTO = "自动路由（推荐）";
        final String CAP_ALL = "全量加载";
        final String CAP_CUSTOM = "自定义…";
        ComboBox<String> capBox = new ComboBox<>();
        capBox.getStyleClass().add("settings-combo");
        capBox.setMaxWidth(Double.MAX_VALUE);
        capBox.getItems().addAll(CAP_AUTO, CAP_ALL, CAP_CUSTOM);
        capBox.setValue(CAP_AUTO);
        TextField capField = dialogField("逗号分隔能力名，如 system,command,web");
        capField.setText("system,command");
        capField.setVisible(false);
        capField.setManaged(false);
        capBox.valueProperty().addListener((obs, o, v) -> {
            boolean custom = CAP_CUSTOM.equals(v);
            capField.setVisible(custom);
            capField.setManaged(custom);
        });

        ComboBox<String> notifBox = new ComboBox<>();
        notifBox.getItems().addAll(TaskNotificationChannel.ORDERED_CHANNELS);
        notifBox.setValue(TaskNotificationChannel.NONE);
        notifBox.getStyleClass().add("settings-combo");
        notifBox.setMaxWidth(Double.MAX_VALUE);

        // 预算 / 能力两列并排（设计稿）
        VBox budgetCol = new VBox(6, fieldLabel("Token 预算", false), budgetBox, budgetField);
        VBox capCol = new VBox(6, fieldLabel("能力工具集", false), capBox, capField);
        HBox.setHgrow(budgetCol, Priority.ALWAYS);
        HBox.setHgrow(capCol, Priority.ALWAYS);
        budgetCol.setMaxWidth(Double.MAX_VALUE);
        capCol.setMaxWidth(Double.MAX_VALUE);
        HBox twoCols = new HBox(16, budgetCol, capCol);

        Button ok = new Button("创建并开始");
        ok.getStyleClass().addAll("jc-btn", "jc-btn-primary");
        ok.setOnAction(e -> {
            String title = safe(titleField.getText());
            String dir = safe(dirField.getText());
            String desc = safe(descArea.getText());
            // 标题、工作目录均可留空：标题空则由描述生成（描述也空则用占位），目录空则起跑时默认 task/ 下新建
            if (title.isEmpty() && desc.isEmpty()) {
                descArea.requestFocus();
                return;
            }
            // 预算：自定义档读 TextField，其余档取预设值
            long budget;
            if (budgetPresets.getOrDefault(budgetBox.getValue(), 0L) == CUSTOM_BUDGET) {
                try { budget = Math.max(0, Long.parseLong(safe(budgetField.getText()))); }
                catch (Exception ex) { budgetField.requestFocus(); return; }
            } else {
                budget = budgetPresets.getOrDefault(budgetBox.getValue(), 120_000L);
            }
            // 能力：自动路由→auto / 全量加载→哨兵 all / 自定义→逗号分隔能力名
            String cap;
            if (CAP_ALL.equals(capBox.getValue())) {
                cap = "all";
            } else if (CAP_CUSTOM.equals(capBox.getValue())) {
                String c = safe(capField.getText());
                cap = c.isEmpty() ? "auto" : c;
            } else {
                cap = "auto";
            }
            final long fBudget = budget;
            final String fCap = cap;
            final String fDir = dir;
            final String fNotif = notifBox.getValue();
            // 标题生成会发起模型请求：整个提交放后台线程，避免卡 UI；先切回列表给即时反馈
            ok.setDisable(true);
            creating = false;
            showPanel(selected != null ? detailPanel : emptyPanel);
            if (selected != null) showDetail(selected);
            Thread th = new Thread(() -> {
                String resolvedTitle = title.isEmpty() ? mgr.generateTitle(desc) : title;
                String stamp = LocalDateTime.now().format(TS);
                SddManagedTask created = mgr.create(resolvedTitle, desc, fCap,
                        fDir.isEmpty() ? null : fDir, fBudget, fNotif, stamp);
                if (created != null) mgr.start(created.id, stamp);
                Platform.runLater(this::refreshList);
            }, "sdd-create");
            th.setDaemon(true);
            th.start();
        });
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().addAll("jc-btn", "jc-btn-ghost");
        cancelBtn.setOnAction(e -> {
            creating = false;
            showPanel(selected != null ? detailPanel : emptyPanel);
            if (selected != null) showDetail(selected);
        });
        HBox btnRow = new HBox(10, ok, cancelBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);
        btnRow.setPadding(new Insets(8, 0, 0, 0));

        VBox form = new VBox(10,
                titleRow, dlgSub,
                fieldLabel("需求描述", true), descArea,
                fieldLabel("标题", false), titleField,
                fieldLabel("工作目录", false), dirRow,
                twoCols,
                fieldLabel("完成通知渠道", false), notifBox,
                btnRow);
        form.getStyleClass().add("sdd-create-form");

        ScrollPane sp = new ScrollPane(form);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.getStyleClass().addAll("settings-scroll-pane", "sdd-detail-scroll");
        VBox.setVgrow(sp, Priority.ALWAYS);
        createPanel.getChildren().setAll(sp);

        showPanel(createPanel);
        descArea.requestFocus();
    }

    // ==================== 事件订阅 ====================

    private void registerListener() {
        mgr.subscribe(new SddTaskListener() {
            @Override public void onTaskChanged(SddManagedTask task) {
                Platform.runLater(() -> {
                    refreshList();
                    if (selected != null && selected.id.equals(task.id)) {
                        selected = task;
                        showDetail(task);
                    }
                });
            }
            @Override public void onLog(String taskId, String taskTitle, String message) {
                Platform.runLater(() -> {
                    if (selected != null && selected.id.equals(taskId)) {
                        appendLogRow(message);
                    }
                });
            }
        });
    }

    // ==================== UI 辅助 ====================

    private Button primary(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("jc-btn", "jc-btn-primary", "jc-btn-sm");
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button secondary(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("jc-btn", "jc-btn-ghost", "jc-btn-sm");
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button cancel(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("jc-btn", "jc-btn-danger", "jc-btn-sm");
        b.setOnAction(e -> action.run());
        return b;
    }

    /** 绿色「保存/正向」语义按钮（如「提升预算并续跑」）。 */
    private Button save(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("jc-btn", "jc-btn-save", "jc-btn-sm");
        b.setOnAction(e -> action.run());
        return b;
    }

    private TextField dialogField(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.getStyleClass().add("task-dialog-field");
        return f;
    }

    private Label fieldLabel(String text, boolean required) {
        Label l = new Label(required ? text + " *" : text);
        l.getStyleClass().add("task-dialog-field-label");
        return l;
    }

    /** 分区标题行：方形小章（?/Δ/✕）+ 标题。 */
    private HBox chipTitle(String chip, String title) {
        Label c = new Label(chip);
        c.getStyleClass().add("sdd-chip-icon");
        Label t = new Label(title);
        t.getStyleClass().add("sdd-section-title");
        HBox row = new HBox(7, c, t);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label mutedLine(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sdd-muted-text");
        l.setWrapText(true);
        return l;
    }

    /** 验收场景卡：panel 头行（能力名 + 通过/待核验徽标）+ G/W/T 着色键行。 */
    private VBox scenarioCard(String capName, Scenario s, boolean passed) {
        Label cap = new Label("能力 · " + blankToDash(capName));
        cap.getStyleClass().add("sdd-scenario-cap");
        cap.setWrapText(true);
        HBox.setHgrow(cap, Priority.ALWAYS);
        cap.setMaxWidth(Double.MAX_VALUE);
        Label verdict = new Label(passed ? "✓ 通过" : "○ 待核验");
        verdict.getStyleClass().addAll("jc-badge", "sdd-card-badge",
                passed ? "jc-badge-running" : "jc-badge-stopped");
        HBox head = new HBox(8, cap, verdict);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("sdd-scenario-head");

        VBox body = new VBox(8,
                gwtRow("Given", s.given(), "sdd-gwt-key-given"),
                gwtRow("When", s.when(), "sdd-gwt-key-when"),
                gwtRow("Then", s.then(), "sdd-gwt-key-then"));
        body.getStyleClass().add("sdd-scenario-body");

        VBox card = new VBox(head, body);
        card.getStyleClass().add("sdd-scenario-card");
        return card;
    }

    /** Given/When/Then 单行：mono 着色键 + 正文。 */
    private HBox gwtRow(String key, String value, String keyColorClass) {
        Label k = new Label(key);
        k.getStyleClass().addAll("sdd-gwt-key", keyColorClass);
        Label v = new Label(blankToDash(value));
        v.getStyleClass().add("sdd-gwt-value");
        v.setWrapText(true);
        HBox row = new HBox(10, k, v);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(v, Priority.ALWAYS);
        return row;
    }

    /** tasks.md 勾选项行：完成划线 / 进行中品牌高亮 + 徽标 / 待办 sunken 底。 */
    private HBox checkRow(TaskItem item, boolean active) {
        boolean done = item.done();
        Label box = new Label(done ? "✓" : "");
        box.getStyleClass().addAll("sdd-checkbox", done ? "sdd-checkbox-done" : "sdd-checkbox-pending");
        box.setAlignment(Pos.CENTER);

        Label text = new Label(item.index() + ". " + item.action());
        text.getStyleClass().add(done ? "sdd-check-text-done"
                : active ? "sdd-check-text-active" : "sdd-check-text-pending");
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(11, box, text);
        row.getStyleClass().add("sdd-check-row");
        if (done) {
            row.getStyleClass().add("sdd-check-row-done");
        } else if (active) {
            row.getStyleClass().add("sdd-check-row-active");
            Label running = new Label("进行中");
            running.getStyleClass().addAll("jc-badge", "sdd-card-badge", "jc-badge-amber");
            row.getChildren().add(running);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** 详情区状态徽标（jc-badge 体系，按状态映射配色与图标）。 */
    private void applyBadge(Label badge, SddTaskState state) {
        badge.setText(badgeLabel(state));
        badge.getStyleClass().setAll("jc-badge", badgeStyle(state));
    }

    /** 列表卡片小徽标（同配色，字号由 sdd-card-badge 收紧）。 */
    private void applyCardBadge(Label badge, SddTaskState state) {
        badge.setText(badgeLabel(state));
        badge.getStyleClass().setAll("jc-badge", "sdd-card-badge", badgeStyle(state));
    }

    /** 状态 → 设计稿徽标文案（含状态点/勾图标）。 */
    private String badgeLabel(SddTaskState state) {
        return switch (state) {
            case RUNNING -> "● 运行中";
            case NEEDS_HUMAN -> "◐ 待人工";
            case COMPLETED -> "✓ 已完成";
            case PAUSED -> "○ 已暂停";
            case FAILED -> "● 失败";
            case PENDING -> "○ 待启动";
            case CANCELLED -> "○ 已取消";
        };
    }

    /** 状态 → jc-badge 配色变体（PENDING/CANCELLED 用 stopped 风格）。 */
    private String badgeStyle(SddTaskState state) {
        return switch (state) {
            case RUNNING -> "jc-badge-running";
            case NEEDS_HUMAN -> "jc-badge-amber";
            case COMPLETED -> "jc-badge-soft";
            case PAUSED -> "jc-badge-stopped";
            case FAILED -> "jc-badge-failed";
            case PENDING, CANCELLED -> "jc-badge-stopped";
        };
    }

    private void applyStylesheet(Scene scene) {
        var chatUrl = getClass().getResource("/css/chat.css");
        if (chatUrl != null) scene.getStylesheets().add(chatUrl.toExternalForm());
        // sdd-task.css 在 chat.css 之后追加加载，覆盖/补充本弹窗专属样式
        var sddUrl = getClass().getResource("/css/sdd-task.css");
        if (sddUrl != null) scene.getStylesheets().add(sddUrl.toExternalForm());
    }

    // ==================== 数据辅助 ====================

    /** whatChanges 多行文本拆为条目（剥离短横、星号、数字序号等列表前缀），供编号卡列渲染。 */
    private static List<String> splitLines(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        s.lines().map(String::trim)
                .filter(x -> !x.isEmpty())
                .map(x -> x.replaceFirst("^[-*•]\\s+", "").replaceFirst("^\\d+[.、)）]\\s*", ""))
                .filter(x -> !x.isEmpty())
                .forEach(out::add);
        return out;
    }

    /** token 数量短格式：863 / 48.2K / 1.2M。 */
    private static String fmtTokens(long n) {
        if (n < 1_000) return String.valueOf(n);
        if (n < 1_000_000) return stripTrailingZero(n / 1_000.0) + "K";
        return stripTrailingZero(n / 1_000_000.0) + "M";
    }

    private static String stripTrailingZero(double v) {
        String s = String.format("%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /**
     * 耗时折算：createdAt → updatedAt（运行中则到当前时刻），返回 {值, 子标签}。
     * 时间戳缺失或不可解析时诚实降级为「—」。
     */
    private String[] elapsedOf(SddManagedTask t) {
        try {
            LocalDateTime created = LocalDateTime.parse(t.createdAt, TS);
            LocalDateTime end = t.state == SddTaskState.RUNNING
                    ? LocalDateTime.now()
                    : LocalDateTime.parse(t.updatedAt, TS);
            Duration d = Duration.between(created, end);
            if (d.isNegative()) return new String[]{"—", ""};
            String sub = switch (t.state) {
                case RUNNING -> "运行中";
                case COMPLETED -> "用时";
                case PAUSED, NEEDS_HUMAN -> "暂停于 " + t.updatedAt.substring(11, 16);
                default -> "累计";
            };
            return new String[]{fmtDuration(d), sub};
        } catch (Exception e) {
            return new String[]{"—", ""};
        }
    }

    private static String fmtDuration(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long mins = d.toMinutesPart();
        long secs = d.toSecondsPart();
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m " + secs + "s";
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
