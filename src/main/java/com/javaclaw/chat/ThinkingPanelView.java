package com.javaclaw.chat;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

/**
 * 右侧思考进度面板
 *
 * <p>显示智能体的实时处理进度，包括：
 * <ul>
 *   <li>当前状态指示（思考中/规划中/执行中/回复中）</li>
 *   <li>主智能体思考过程</li>
 *   <li>执行规划和子任务进度</li>
 *   <li>子智能体思考过程和执行状态</li>
 * </ul>
 * </p>
 */
public class ThinkingPanelView {

    private final VBox root;
    private final VBox contentBox;
    private final ScrollPane scrollPane;

    // ==================== 状态指示 ====================

    /** 状态指示灯 */
    private final Label statusDot;

    /** 状态文字 */
    private final Label statusLabel;

    // ==================== 思考区域 ====================

    /** 主智能体思考容器 */
    private VBox thinkingSection;

    /** 主智能体思考文本 */
    private Text thinkingText;

    /** 主思考区域折叠控制 */
    private Runnable collapseThinking;

    // ==================== 规划区域 ====================

    /** 规划容器 */
    private VBox planSection;

    /** 规划文本 */
    private Text planText;

    /** 规划区域折叠控制 */
    private Runnable collapsePlan;

    // ==================== 子智能体进度区域 ====================

    /** 子智能体进度列表容器 */
    private VBox agentProgressBox;

    /** 当前子智能体名称 */
    private String currentAgentName;

    /** 当前子智能体思考文本 */
    private Text currentAgentThinkingText;

    /** 当前子智能体状态标签 */
    private Label currentAgentStatusLabel;

    /** 当前子智能体容器 */
    private VBox currentAgentSection;

    /** 当前子智能体内容折叠控制（用于完成时自动折叠） */
    private Runnable collapseCurrentAgent;

    /** 面板标题标签（用于显示空状态） */
    private final Label emptyHint;

    // ==================== 设计稿状态卡片（tag + elapsed + 阶段 pill + metrics 三联） ====================

    /** 状态卡片整体容器 */
    private VBox statusCard;

    /** 状态标签（如"处理完成" / "进行中"） */
    private Label statusTag;

    /** 已耗时文字（mono，例如 "4.2s"） */
    private Label elapsedLabel;

    /** 阶段 pill 行（思考 · 规划 · 执行 · 回复） */
    private HBox stagePillRow;

    /** Tokens In 数值 */
    private Label tokensInValue;

    /** Tokens Out 数值 */
    private Label tokensOutValue;

    /** 费用数值 */
    private Label costValue;

    /** 计时开始时刻（startNewStream 时设置） */
    private long streamStartMillis;

    /** 已耗时轮询（每 100ms 刷新） */
    private Timeline elapsedTicker;

    /** 工具调用累计数（渲染顺序标号用） */
    private int toolCallSeq;

    // ==================== 管线进度（设计稿：发送 → 收到首字 之间的步骤可视化） ====================

    /** 管线进度容器（按 stageId 维护行；首次收到事件时创建） */
    private VBox pipelineBox;

    /** stageId → 行容器，便于原地更新状态 */
    private final java.util.Map<String, PipelineStageRow> pipelineRows = new java.util.LinkedHashMap<>();

    /** 管线进度区域折叠回调 */
    private Runnable collapsePipeline;

    public ThinkingPanelView() {
        // ==================== 面板标题 ====================
        Label titleLabel = new Label("处理进度");
        titleLabel.getStyleClass().add("thinking-panel-title");

        // 状态指示条
        statusDot = new Label("●");
        statusDot.getStyleClass().add("thinking-panel-status-dot");

        statusLabel = new Label("等待中");
        statusLabel.getStyleClass().add("thinking-panel-status-text");

        HBox statusBar = new HBox(6, statusDot, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("thinking-panel-status-bar");

        // 标题栏
        HBox titleBar = new HBox(titleLabel);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 16, 10, 16));

        // ==================== 空状态提示 ====================
        emptyHint = new Label("发送消息后将在此显示\n智能体的思考与处理进度");
        emptyHint.getStyleClass().add("thinking-panel-empty-hint");
        emptyHint.setWrapText(true);

        // ==================== 内容滚动区域 ====================
        contentBox = new VBox(8);
        contentBox.setPadding(new Insets(0, 12, 12, 12));

        // 设计稿顶部状态卡片（tag + elapsed + pill 行 + 指标三联）
        statusCard = buildStatusCard();

        // 状态图例（保留：旧逻辑兼容）
        HBox legend = createStatusLegend();

        contentBox.getChildren().addAll(statusCard, statusBar, legend, emptyHint);

        scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("thinking-panel-scroll");

        // 自动滚动到底部
        contentBox.heightProperty().addListener((obs, oldVal, newVal) ->
                scrollPane.setVvalue(1.0));

        // ==================== 组装根节点 ====================
        root = new VBox(0, titleBar, scrollPane);
        root.getStyleClass().add("thinking-panel-root");
        root.setPrefWidth(280);
        root.setMinWidth(240);
        root.setMaxWidth(320);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    /**
     * 获取根节点
     */
    public VBox getRoot() {
        return root;
    }

    // ==================== 状态控制 ====================

    /**
     * 开始新的流式会话 — 清空之前的内容，准备接收新数据
     */
    public void startNewStream() {
        contentBox.getChildren().clear();

        // 重新添加状态栏
        HBox statusBar = new HBox(6, statusDot, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("thinking-panel-status-bar");

        // 重新添加状态图例
        HBox legend = createStatusLegend();

        contentBox.getChildren().addAll(statusCard, statusBar, legend);

        // 重置状态 + 指标 + 耗时计时
        setStatus("thinking", "思考中...");
        updateMetrics(0, 0, "¥0.00");
        toolCallSeq = 0;
        streamStartMillis = System.currentTimeMillis();
        startElapsedTicker();

        // 清除引用
        thinkingSection = null;
        thinkingText = null;
        collapseThinking = null;
        planSection = null;
        planText = null;
        collapsePlan = null;
        agentProgressBox = null;
        currentAgentName = null;
        currentAgentThinkingText = null;
        currentAgentStatusLabel = null;
        currentAgentSection = null;
        collapseCurrentAgent = null;
        pipelineBox = null;
        pipelineRows.clear();
        collapsePipeline = null;

        emptyHint.setVisible(false);
        emptyHint.setManaged(false);
    }

    /**
     * 流式会话结束：自动折叠思考区/规划区，减少阅读疲劳
     */
    public void endStream() {
        setStatus("idle", "处理完成");
        stopElapsedTicker();
        // 定格最终耗时
        refreshElapsedLabel();
        if (collapseThinking != null) collapseThinking.run();
        if (collapsePlan != null) collapsePlan.run();
        if (collapseCurrentAgent != null) collapseCurrentAgent.run();
        if (collapsePipeline != null) collapsePipeline.run();
        currentAgentName = null;
        currentAgentThinkingText = null;
        currentAgentStatusLabel = null;
        currentAgentSection = null;
        collapseCurrentAgent = null;
    }

    /**
     * 启动 100ms 粒度的耗时轮询，驱动 elapsedLabel 刷新。
     */
    private void startElapsedTicker() {
        stopElapsedTicker();
        elapsedTicker = new Timeline(new KeyFrame(Duration.millis(100), e -> refreshElapsedLabel()));
        elapsedTicker.setCycleCount(Animation.INDEFINITE);
        elapsedTicker.play();
    }

    private void stopElapsedTicker() {
        if (elapsedTicker != null) {
            elapsedTicker.stop();
            elapsedTicker = null;
        }
    }

    private void refreshElapsedLabel() {
        if (elapsedLabel == null) return;
        long ms = System.currentTimeMillis() - streamStartMillis;
        if (ms < 60_000) {
            elapsedLabel.setText(String.format("%.1fs", ms / 1000.0));
        } else {
            long sec = ms / 1000;
            elapsedLabel.setText((sec / 60) + "m " + (sec % 60) + "s");
        }
    }

    /**
     * 重置为空状态
     */
    public void reset() {
        contentBox.getChildren().clear();

        HBox statusBar = new HBox(6, statusDot, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("thinking-panel-status-bar");

        emptyHint.setVisible(true);
        emptyHint.setManaged(true);

        // 重新添加状态图例
        HBox legend = createStatusLegend();

        contentBox.getChildren().addAll(statusCard, statusBar, legend, emptyHint);
        setStatus("idle", "等待中");
        stopElapsedTicker();
        updateMetrics(0, 0, "¥0.00");
        if (elapsedLabel != null) elapsedLabel.setText("0.0s");
        toolCallSeq = 0;

        thinkingSection = null;
        thinkingText = null;
        collapseThinking = null;
        planSection = null;
        planText = null;
        collapsePlan = null;
        agentProgressBox = null;
        currentAgentName = null;
        currentAgentThinkingText = null;
        currentAgentStatusLabel = null;
        currentAgentSection = null;
        collapseCurrentAgent = null;
        pipelineBox = null;
        pipelineRows.clear();
        collapsePipeline = null;
    }

    /**
     * 设置状态
     *
     * @param type  状态类型: thinking / planning / executing / replying / idle
     * @param text  状态文字
     */
    public void setStatus(String type, String text) {
        statusLabel.setText(text);
        // 根据类型设置不同颜色的状态点
        statusDot.getStyleClass().removeAll(
                "status-thinking", "status-planning", "status-executing",
                "status-replying", "status-idle");
        statusDot.getStyleClass().add("status-" + type);
        // 同步刷新设计稿顶部的 tag
        updateStatusTag(type, text);
    }

    // ==================== 管线进度（设计稿） ====================

    /**
     * 记录/更新一个管线阶段的进度。
     * 首次收到某 stageId 时新建一行；同 stageId 重复收到时原地刷新状态与详情。
     *
     * <p>这是给 UI 层的统一入口，由 {@code ChatViewController} 在收到
     * {@link com.javaclaw.api.conversation.ConversationEvent.Progress} 事件时调用。
     * 调用方需保证已在 JavaFX Application Thread。</p>
     *
     * @param stageId  阶段稳定标识（同一阶段重复事件用此匹配行）
     * @param label    阶段显示名（首次添加时使用；后续刷新可传同值）
     * @param status   状态：running / done / skipped / error
     * @param detail   可选详情（结果摘要、跳过原因、错误消息）
     */
    public void recordPipelineProgress(String stageId, String label, String status, String detail) {
        ensurePipelineBox();
        PipelineStageRow row = pipelineRows.get(stageId);
        if (row == null) {
            row = createPipelineRow(stageId, label);
            pipelineRows.put(stageId, row);
            pipelineBox.getChildren().add(row.container);
        }
        row.update(status, detail);
    }

    private void ensurePipelineBox() {
        if (pipelineBox == null) {
            pipelineBox = new VBox(4);
            pipelineBox.setPadding(new Insets(4, 0, 0, 0));

            CollapsibleSection wrapper = buildCollapsibleSection(
                    "🛠 管线进度", "tp-pipeline-section", pipelineBox);
            collapsePipeline = wrapper.collapse;
            // 把管线进度放在 statusCard 之后、其他 section 之前；
            // 若无法定位则简单 append 到末尾
            int insertAt = contentBox.getChildren().size();
            for (int i = 0; i < contentBox.getChildren().size(); i++) {
                if (contentBox.getChildren().get(i) == statusCard) {
                    insertAt = i + 1;
                    break;
                }
            }
            // 跳过紧跟在 statusCard 后的 statusBar / legend，避免插到中间
            while (insertAt < contentBox.getChildren().size()) {
                var node = contentBox.getChildren().get(insertAt);
                String cls = node.getStyleClass().isEmpty() ? "" : node.getStyleClass().get(0);
                if ("thinking-panel-status-bar".equals(cls) || "tp-status-legend".equals(cls)) {
                    insertAt++;
                } else {
                    break;
                }
            }
            contentBox.getChildren().add(insertAt, wrapper.container);
        }
    }

    private PipelineStageRow createPipelineRow(String stageId, String label) {
        Label dot = new Label("●");
        dot.getStyleClass().addAll("tp-pipeline-dot", "tp-pipeline-dot-idle");

        Label nameLabel = new Label(label == null ? stageId : label);
        nameLabel.getStyleClass().add("tp-pipeline-name");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label statusBadge = new Label("待命");
        statusBadge.getStyleClass().addAll("tp-pipeline-status", "tp-pipeline-status-idle");

        HBox headerRow = new HBox(6, dot, nameLabel, spacer, statusBadge);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        Label detailLabel = new Label();
        detailLabel.getStyleClass().add("tp-pipeline-detail");
        detailLabel.setWrapText(true);
        detailLabel.setMaxWidth(230);
        detailLabel.setVisible(false);
        detailLabel.setManaged(false);

        VBox container = new VBox(2, headerRow, detailLabel);
        container.getStyleClass().add("tp-pipeline-row");
        container.setPadding(new Insets(3, 6, 3, 6));

        return new PipelineStageRow(container, dot, statusBadge, detailLabel);
    }

    /** 一行管线阶段：容器 + 状态点 + 状态徽章 + 详情标签 */
    private static final class PipelineStageRow {
        final VBox container;
        final Label dot;
        final Label statusBadge;
        final Label detailLabel;

        PipelineStageRow(VBox container, Label dot, Label statusBadge, Label detailLabel) {
            this.container = container;
            this.dot = dot;
            this.statusBadge = statusBadge;
            this.detailLabel = detailLabel;
        }

        void update(String status, String detail) {
            String s = status == null ? "running" : status.toLowerCase();
            // 状态点配色
            dot.getStyleClass().removeAll(
                    "tp-pipeline-dot-idle", "tp-pipeline-dot-running",
                    "tp-pipeline-dot-done", "tp-pipeline-dot-skipped",
                    "tp-pipeline-dot-error");
            dot.getStyleClass().add("tp-pipeline-dot-" + s);

            // 状态徽章
            statusBadge.getStyleClass().removeAll(
                    "tp-pipeline-status-idle", "tp-pipeline-status-running",
                    "tp-pipeline-status-done", "tp-pipeline-status-skipped",
                    "tp-pipeline-status-error");
            statusBadge.getStyleClass().add("tp-pipeline-status-" + s);
            statusBadge.setText(switch (s) {
                case "running" -> "进行中";
                case "done" -> "完成";
                case "skipped" -> "跳过";
                case "error" -> "失败";
                default -> "—";
            });

            // 详情：有则显示，无则隐藏（保持卡片紧凑）
            if (detail != null && !detail.isBlank()) {
                detailLabel.setText(detail);
                detailLabel.setVisible(true);
                detailLabel.setManaged(true);
            } else {
                detailLabel.setText("");
                detailLabel.setVisible(false);
                detailLabel.setManaged(false);
            }
        }
    }

    // ==================== 思考内容 ====================

    /**
     * 追加主智能体思考内容
     */
    public void appendThinking(String chunk) {
        if (thinkingSection == null) {
            createThinkingSection();
        }
        setStatus("thinking", "思考中...");
        thinkingText.setText(thinkingText.getText() + chunk);
    }

    private void createThinkingSection() {
        thinkingText = new Text();
        thinkingText.getStyleClass().add("tp-thinking-text");
        TextFlow textFlow = new TextFlow(thinkingText);
        textFlow.setMaxWidth(250);

        VBox content = new VBox(4, textFlow);
        content.setPadding(new Insets(4, 0, 0, 0));

        CollapsibleSection section = buildCollapsibleSection("💭 思考过程", "tp-thinking-section", content);
        thinkingSection = section.container;
        collapseThinking = section.collapse;
        contentBox.getChildren().add(thinkingSection);
    }

    // ==================== 规划内容 ====================

    /**
     * 更新执行规划内容（全量替换）
     */
    public void updatePlan(String hint) {
        if (planSection == null) {
            createPlanSection();
        }
        setStatus("planning", "规划中...");
        planText.setText(hint);
    }

    private void createPlanSection() {
        planText = new Text();
        planText.getStyleClass().add("tp-plan-text");
        TextFlow textFlow = new TextFlow(planText);
        textFlow.setMaxWidth(250);

        VBox content = new VBox(4, textFlow);
        content.setPadding(new Insets(4, 0, 0, 0));

        CollapsibleSection section = buildCollapsibleSection("📋 执行规划", "tp-plan-section", content);
        planSection = section.container;
        collapsePlan = section.collapse;
        contentBox.getChildren().add(planSection);
    }

    // ==================== 子智能体进度 ====================

    /**
     * 追加子智能体思考内容
     *
     * @param agentName  子智能体显示名称
     * @param thinking   思考文本
     */
    public void appendSubAgentThinking(String agentName, String thinking) {
        ensureAgentProgressBox();
        ensureAgentSection(agentName);

        setStatus("executing", agentName + " 思考中...");

        if (currentAgentStatusLabel != null) {
            currentAgentStatusLabel.setText("思考中...");
            currentAgentStatusLabel.getStyleClass().removeAll("agent-status-done");
            currentAgentStatusLabel.getStyleClass().add("agent-status-thinking");
        }

        if (currentAgentThinkingText != null) {
            currentAgentThinkingText.setText(currentAgentThinkingText.getText() + thinking);
        }
    }

    /**
     * 标记子智能体已返回结果
     *
     * @param agentName  子智能体显示名称
     * @param briefResult  简要结果描述
     */
    public void markSubAgentResult(String agentName, String briefResult) {
        ensureAgentProgressBox();
        ensureAgentSection(agentName);

        if (currentAgentStatusLabel != null) {
            currentAgentStatusLabel.setText("已完成");
            currentAgentStatusLabel.getStyleClass().removeAll("agent-status-thinking");
            currentAgentStatusLabel.getStyleClass().add("agent-status-done");
        }

        // 添加简要结果标签（放在卡片底部，折叠后仍作为摘要可见）
        if (briefResult != null && !briefResult.isEmpty() && currentAgentSection != null) {
            String display = briefResult.length() > 80
                    ? briefResult.substring(0, 77) + "..."
                    : briefResult;
            Label resultLabel = new Label(display);
            resultLabel.getStyleClass().add("tp-agent-result-brief");
            resultLabel.setWrapText(true);
            resultLabel.setMaxWidth(230);
            currentAgentSection.getChildren().add(resultLabel);
        }

        // 完成后自动折叠思考内容，保留 header + 结果摘要
        if (collapseCurrentAgent != null) {
            collapseCurrentAgent.run();
        }
    }

    /**
     * 更新回复状态
     */
    public void setReplying() {
        setStatus("replying", "回复中...");
    }

    private void ensureAgentProgressBox() {
        if (agentProgressBox == null) {
            agentProgressBox = new VBox(6);
            agentProgressBox.setPadding(new Insets(4, 0, 0, 0));

            CollapsibleSection wrapper = buildCollapsibleSection("🤖 智能体执行", "tp-agents-section", agentProgressBox);
            contentBox.getChildren().add(wrapper.container);
        }
    }

    // ==================== 辅助方法 ====================

    // ==================== 状态卡片（设计稿） ====================

    /**
     * 构建顶部状态卡片：tag + elapsed + 阶段 pill 行 + TOKENS IN/OUT/费用 三联指标。
     */
    private VBox buildStatusCard() {
        // 第一行：状态标签 + 已耗时
        statusTag = new Label("等待中");
        statusTag.getStyleClass().addAll("tp-status-tag", "tp-status-tag-idle");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        elapsedLabel = new Label("0.0s");
        elapsedLabel.getStyleClass().add("tp-elapsed");

        HBox row1 = new HBox(8, statusTag, spacer, elapsedLabel);
        row1.setAlignment(Pos.CENTER_LEFT);

        // 第二行：四个阶段 pill（思考 / 规划 / 执行 / 回复）
        stagePillRow = new HBox(4);
        stagePillRow.setAlignment(Pos.CENTER_LEFT);
        stagePillRow.getChildren().addAll(
                createStagePill("思考", "thinking"),
                createStagePill("规划", "planning"),
                createStagePill("执行", "executing"),
                createStagePill("回复", "replying")
        );

        // 第三行：三联指标
        tokensInValue = new Label("0");
        tokensInValue.getStyleClass().add("tp-metric-value");
        tokensOutValue = new Label("0");
        tokensOutValue.getStyleClass().add("tp-metric-value");
        costValue = new Label("¥0.00");
        costValue.getStyleClass().add("tp-metric-value");

        GridPane metrics = new GridPane();
        metrics.setHgap(8);
        metrics.getStyleClass().add("tp-metrics");
        metrics.add(buildMetricCell("TOKENS IN", tokensInValue), 0, 0);
        metrics.add(buildMetricCell("TOKENS OUT", tokensOutValue), 1, 0);
        metrics.add(buildMetricCell("费用", costValue), 2, 0);
        javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
        c1.setPercentWidth(33.33);
        javafx.scene.layout.ColumnConstraints c2 = new javafx.scene.layout.ColumnConstraints();
        c2.setPercentWidth(33.33);
        javafx.scene.layout.ColumnConstraints c3 = new javafx.scene.layout.ColumnConstraints();
        c3.setPercentWidth(33.34);
        metrics.getColumnConstraints().addAll(c1, c2, c3);

        VBox card = new VBox(8, row1, stagePillRow, metrics);
        card.getStyleClass().add("tp-status-card");
        card.setPadding(new Insets(10, 12, 10, 12));
        return card;
    }

    private HBox createStagePill(String label, String color) {
        Label dot = new Label("●");
        dot.getStyleClass().addAll("tp-stage-pill-dot", "status-" + color);
        Label text = new Label(label);
        text.getStyleClass().add("tp-stage-pill-text");
        HBox pill = new HBox(4, dot, text);
        pill.getStyleClass().addAll("tp-stage-pill", "tp-stage-pill-" + color);
        pill.setAlignment(Pos.CENTER_LEFT);
        pill.setPadding(new Insets(2, 8, 2, 8));
        return pill;
    }

    private VBox buildMetricCell(String label, Label valueLabel) {
        Label lbl = new Label(label);
        lbl.getStyleClass().add("tp-metric-label");
        VBox cell = new VBox(2, lbl, valueLabel);
        cell.setAlignment(Pos.CENTER_LEFT);
        return cell;
    }

    /**
     * 刷新顶部状态卡片的 tag + 已耗时显示。
     */
    private void updateStatusTag(String type, String text) {
        if (statusTag == null) return;
        statusTag.setText(text);
        statusTag.getStyleClass().removeAll(
                "tp-status-tag-idle", "tp-status-tag-thinking", "tp-status-tag-planning",
                "tp-status-tag-executing", "tp-status-tag-replying");
        statusTag.getStyleClass().add("tp-status-tag-" + type);
    }

    /**
     * 更新指标（Tokens In / Out / 费用）。外部可随流式响应持续调用。
     */
    public void updateMetrics(long tokensIn, long tokensOut, String costText) {
        if (tokensInValue != null) tokensInValue.setText(Long.toString(tokensIn));
        if (tokensOutValue != null) tokensOutValue.setText(Long.toString(tokensOut));
        if (costValue != null && costText != null) costValue.setText(costText);
    }

    /**
     * 追加一条工具调用 mono 行到"智能体执行"区域（设计稿中的
     * ⚡ name · input · status 行）。如"智能体执行"区域尚未存在则创建。
     */
    public void appendToolCall(String name, String input, String status) {
        ensureAgentProgressBox();
        toolCallSeq++;

        Label bolt = new Label("⚡");
        bolt.getStyleClass().add("tp-tool-bolt");

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("tp-tool-name");

        Label inputLabel = new Label(input == null ? "" : input);
        inputLabel.getStyleClass().add("tp-tool-input");
        inputLabel.setMaxWidth(130);
        HBox.setHgrow(inputLabel, Priority.ALWAYS);

        Label statusLabel = new Label(status == null ? "ok" : status);
        statusLabel.getStyleClass().addAll("tp-tool-status", "tp-tool-status-" + statusSlug(status));

        HBox row = new HBox(6, bolt, nameLabel, inputLabel, statusLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("tp-tool-row");
        row.setPadding(new Insets(4, 8, 4, 8));
        agentProgressBox.getChildren().add(row);
    }

    private String statusSlug(String status) {
        if (status == null) return "ok";
        String lower = status.toLowerCase();
        if (lower.contains("ok") || lower.contains("完成") || lower.contains("success")) return "ok";
        if (lower.contains("error") || lower.contains("失败") || lower.contains("fail")) return "error";
        return "running";
    }

    /**
     * 创建状态图例
     */
    private HBox createStatusLegend() {
        HBox legend = new HBox(8);
        legend.setAlignment(Pos.CENTER_LEFT);
        legend.getStyleClass().add("tp-status-legend");
        legend.setPadding(new Insets(2, 10, 6, 10));

        legend.getChildren().addAll(
                createLegendItem("●", "status-thinking", "思考"),
                createLegendItem("●", "status-planning", "规划"),
                createLegendItem("●", "status-executing", "执行"),
                createLegendItem("●", "status-replying", "回复"),
                createLegendItem("●", "status-idle", "空闲")
        );
        return legend;
    }

    /**
     * 创建图例条目
     */
    private HBox createLegendItem(String dot, String dotClass, String text) {
        Label dotLabel = new Label(dot);
        dotLabel.getStyleClass().addAll("tp-legend-dot", dotClass);
        Label textLabel = new Label(text);
        textLabel.getStyleClass().add("tp-legend-text");
        HBox item = new HBox(3, dotLabel, textLabel);
        item.setAlignment(Pos.CENTER_LEFT);
        return item;
    }

    /**
     * 可折叠区域的创建结果：容器 + 程序化折叠回调
     */
    private static final class CollapsibleSection {
        final VBox container;
        final Runnable collapse;
        CollapsibleSection(VBox container, Runnable collapse) {
            this.container = container;
            this.collapse = collapse;
        }
    }

    /**
     * 创建可折叠的 section（支持程序化折叠）
     */
    private CollapsibleSection buildCollapsibleSection(String title, String sectionStyleClass, javafx.scene.Node contentPane) {
        Label arrow = new Label("▼");
        arrow.getStyleClass().add("tp-collapse-arrow");

        Label header = new Label(title);
        header.getStyleClass().add("tp-section-header");

        HBox headerRow = new HBox(4, arrow, header);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.getStyleClass().add("tp-collapsible-header");
        headerRow.setCursor(javafx.scene.Cursor.HAND);

        // 点击切换折叠
        headerRow.setOnMouseClicked(e -> {
            boolean expand = !contentPane.isVisible();
            contentPane.setVisible(expand);
            contentPane.setManaged(expand);
            arrow.setText(expand ? "▼" : "▶");
        });

        VBox section = new VBox(4, headerRow, contentPane);
        section.getStyleClass().add(sectionStyleClass);
        section.setPadding(new Insets(8, 10, 8, 10));

        Runnable collapse = () -> {
            if (contentPane.isVisible()) {
                contentPane.setVisible(false);
                contentPane.setManaged(false);
                arrow.setText("▶");
            }
        };
        return new CollapsibleSection(section, collapse);
    }

    private void ensureAgentSection(String agentName) {
        // 如果是同一个智能体，复用现有区域
        if (agentName != null && agentName.equals(currentAgentName)) {
            return;
        }

        currentAgentName = agentName;

        // 创建新的智能体进度条目
        Label nameLabel = new Label(agentName);
        nameLabel.getStyleClass().add("tp-agent-name");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        currentAgentStatusLabel = new Label("执行中...");
        currentAgentStatusLabel.getStyleClass().addAll("tp-agent-status", "agent-status-thinking");

        Label agentArrow = new Label("▼");
        agentArrow.getStyleClass().add("tp-collapse-arrow");

        HBox headerRow = new HBox(6, agentArrow, nameLabel, spacer, currentAgentStatusLabel);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setCursor(javafx.scene.Cursor.HAND);

        currentAgentThinkingText = new Text();
        currentAgentThinkingText.getStyleClass().add("tp-agent-thinking-text");
        TextFlow thinkingFlow = new TextFlow(currentAgentThinkingText);
        thinkingFlow.setMaxWidth(230);

        // 思考内容容器（可折叠）
        VBox agentBodyBox = new VBox(3, thinkingFlow);
        agentBodyBox.setPadding(new Insets(2, 0, 0, 12));

        headerRow.setOnMouseClicked(e -> {
            boolean expand = !agentBodyBox.isVisible();
            agentBodyBox.setVisible(expand);
            agentBodyBox.setManaged(expand);
            agentArrow.setText(expand ? "▼" : "▶");
        });

        currentAgentSection = new VBox(3, headerRow, agentBodyBox);
        currentAgentSection.getStyleClass().add("tp-agent-card");
        currentAgentSection.setPadding(new Insets(6, 8, 6, 8));

        // 保存折叠回调，完成时调用
        collapseCurrentAgent = () -> {
            if (agentBodyBox.isVisible()) {
                agentBodyBox.setVisible(false);
                agentBodyBox.setManaged(false);
                agentArrow.setText("▶");
            }
        };

        agentProgressBox.getChildren().add(currentAgentSection);
    }
}
