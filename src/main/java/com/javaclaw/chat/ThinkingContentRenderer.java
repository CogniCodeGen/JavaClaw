package com.javaclaw.chat;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 把流式思考事件渲染到 FXML 预留的动态内容容器。
 *
 * <p>FXML 负责固定框架；思考段、规划段、工具调用和任意数量的智能体卡片只能在运行时
 * 得知，因此由本渲染器创建 Node。实例受 FX 线程约束，不持有业务服务。</p>
 */
final class ThinkingContentRenderer {

    private static final int RESULT_PREVIEW_LIMIT = 80;

    private final VBox sections;
    private final Map<String, PipelineStageRow> pipelineRows = new LinkedHashMap<>();

    private VBox pipelineBox;
    private Runnable collapsePipeline;
    private VBox thinkingSection;
    private Text thinkingText;
    private Runnable collapseThinking;
    private VBox planSection;
    private Text planText;
    private Runnable collapsePlan;
    private VBox agentProgressBox;
    private String currentAgentName;
    private Text currentAgentThinkingText;
    private Label currentAgentStatusLabel;
    private VBox currentAgentSection;
    private Runnable collapseCurrentAgent;

    ThinkingContentRenderer(VBox sections) {
        this.sections = Objects.requireNonNull(sections, "sections");
    }

    void clear() {
        sections.getChildren().clear();
        pipelineRows.clear();
        pipelineBox = null;
        collapsePipeline = null;
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
    }

    void finish(String agentStatusText) {
        if (currentAgentStatusLabel != null
                && currentAgentStatusLabel.getStyleClass().contains("agent-status-thinking")) {
            currentAgentStatusLabel.setText(agentStatusText);
            currentAgentStatusLabel.getStyleClass().remove("agent-status-thinking");
            currentAgentStatusLabel.getStyleClass().add("agent-status-done");
        }
        collapse(collapseThinking);
        collapse(collapsePlan);
        collapse(collapseCurrentAgent);
        collapse(collapsePipeline);
        currentAgentName = null;
        currentAgentThinkingText = null;
        currentAgentStatusLabel = null;
        currentAgentSection = null;
        collapseCurrentAgent = null;
    }

    void recordPipelineProgress(String stageId, String label, String status, String detail) {
        Objects.requireNonNull(stageId, "stageId");
        ensurePipelineBox();
        PipelineStageRow row = pipelineRows.computeIfAbsent(stageId, ignored -> {
            PipelineStageRow created = createPipelineRow(stageId, label);
            pipelineBox.getChildren().add(created.container());
            return created;
        });
        row.update(status, detail);
    }

    void appendThinking(String chunk) {
        if (thinkingSection == null) {
            TextFlow flow = createTextFlow("tp-thinking-text");
            thinkingText = (Text) flow.getChildren().getFirst();
            VBox content = paddedContent(flow);
            CollapsibleSection section = collapsible(
                    "💭 思考过程", "tp-thinking-section", content);
            thinkingSection = section.container();
            collapseThinking = section.collapse();
            sections.getChildren().add(thinkingSection);
        }
        thinkingText.setText(thinkingText.getText() + safe(chunk));
    }

    void updatePlan(String hint) {
        if (planSection == null) {
            TextFlow flow = createTextFlow("tp-plan-text");
            planText = (Text) flow.getChildren().getFirst();
            VBox content = paddedContent(flow);
            CollapsibleSection section = collapsible(
                    "📋 执行规划", "tp-plan-section", content);
            planSection = section.container();
            collapsePlan = section.collapse();
            sections.getChildren().add(planSection);
        }
        planText.setText(safe(hint));
    }

    void appendSubAgentThinking(String agentName, String thinking) {
        ensureAgentProgressBox();
        ensureAgentSection(agentName);
        currentAgentStatusLabel.setText("思考中...");
        currentAgentStatusLabel.getStyleClass().remove("agent-status-done");
        if (!currentAgentStatusLabel.getStyleClass().contains("agent-status-thinking")) {
            currentAgentStatusLabel.getStyleClass().add("agent-status-thinking");
        }
        currentAgentThinkingText.setText(
                currentAgentThinkingText.getText() + safe(thinking));
    }

    void markSubAgentResult(String agentName, String briefResult) {
        ensureAgentProgressBox();
        ensureAgentSection(agentName);
        currentAgentStatusLabel.setText("已完成");
        currentAgentStatusLabel.getStyleClass().remove("agent-status-thinking");
        if (!currentAgentStatusLabel.getStyleClass().contains("agent-status-done")) {
            currentAgentStatusLabel.getStyleClass().add("agent-status-done");
        }
        if (briefResult != null && !briefResult.isEmpty()) {
            String display = briefResult.length() > RESULT_PREVIEW_LIMIT
                    ? briefResult.substring(0, RESULT_PREVIEW_LIMIT - 3) + "..."
                    : briefResult;
            Label result = new Label(display);
            result.getStyleClass().add("tp-agent-result-brief");
            result.setWrapText(true);
            result.setMaxWidth(230);
            currentAgentSection.getChildren().add(result);
        }
        collapse(collapseCurrentAgent);
    }

    void appendToolCall(String name, String input, String status) {
        ensureAgentProgressBox();
        Label bolt = styledLabel("⚡", "tp-tool-bolt");
        Label nameLabel = styledLabel(safe(name), "tp-tool-name");
        Label inputLabel = styledLabel(safe(input), "tp-tool-input");
        inputLabel.setMaxWidth(130);
        HBox.setHgrow(inputLabel, Priority.ALWAYS);
        Label statusLabel = styledLabel(status == null ? "ok" : status, "tp-tool-status");
        statusLabel.getStyleClass().add("tp-tool-status-" + statusSlug(status));

        HBox row = new HBox(6, bolt, nameLabel, inputLabel, statusLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("tp-tool-row");
        row.setPadding(new Insets(4, 8, 4, 8));
        agentProgressBox.getChildren().add(row);
    }

    private void ensurePipelineBox() {
        if (pipelineBox != null) return;
        pipelineBox = new VBox(4);
        pipelineBox.setPadding(new Insets(4, 0, 0, 0));
        CollapsibleSection section = collapsible(
                "🛠 管线进度", "tp-pipeline-section", pipelineBox);
        collapsePipeline = section.collapse();
        sections.getChildren().addFirst(section.container());
    }

    private PipelineStageRow createPipelineRow(String stageId, String label) {
        Label dot = styledLabel("●", "tp-pipeline-dot");
        dot.getStyleClass().add("tp-pipeline-dot-idle");
        Label name = styledLabel(label == null ? stageId : label, "tp-pipeline-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label status = styledLabel("待命", "tp-pipeline-status");
        status.getStyleClass().add("tp-pipeline-status-idle");
        HBox header = new HBox(6, dot, name, spacer, status);
        header.setAlignment(Pos.CENTER_LEFT);

        Label detail = styledLabel("", "tp-pipeline-detail");
        detail.setWrapText(true);
        detail.setMaxWidth(230);
        detail.setVisible(false);
        detail.setManaged(false);
        VBox row = new VBox(2, header, detail);
        row.getStyleClass().add("tp-pipeline-row");
        row.setPadding(new Insets(3, 6, 3, 6));
        return new PipelineStageRow(row, dot, status, detail);
    }

    private void ensureAgentProgressBox() {
        if (agentProgressBox != null) return;
        agentProgressBox = new VBox(6);
        agentProgressBox.setPadding(new Insets(4, 0, 0, 0));
        CollapsibleSection section = collapsible(
                "🤖 智能体执行", "tp-agents-section", agentProgressBox);
        sections.getChildren().add(section.container());
    }

    private void ensureAgentSection(String agentName) {
        if (currentAgentSection != null && Objects.equals(agentName, currentAgentName)) return;
        currentAgentName = agentName;
        Label name = styledLabel(agentName == null ? "智能体" : agentName, "tp-agent-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        currentAgentStatusLabel = styledLabel("执行中...", "tp-agent-status");
        currentAgentStatusLabel.getStyleClass().add("agent-status-thinking");
        Label arrow = styledLabel("▼", "tp-collapse-arrow");
        HBox header = new HBox(6, arrow, name, spacer, currentAgentStatusLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setCursor(Cursor.HAND);

        currentAgentThinkingText = new Text();
        currentAgentThinkingText.getStyleClass().add("tp-agent-thinking-text");
        TextFlow flow = new TextFlow(currentAgentThinkingText);
        flow.setMaxWidth(230);
        VBox body = new VBox(3, flow);
        body.setPadding(new Insets(2, 0, 0, 12));
        Runnable toggle = () -> setExpanded(body, arrow, !body.isVisible());
        header.setOnMouseClicked(event -> toggle.run());

        currentAgentSection = new VBox(3, header, body);
        currentAgentSection.getStyleClass().add("tp-agent-card");
        currentAgentSection.setPadding(new Insets(6, 8, 6, 8));
        collapseCurrentAgent = () -> setExpanded(body, arrow, false);
        agentProgressBox.getChildren().add(currentAgentSection);
    }

    private static CollapsibleSection collapsible(
            String title, String styleClass, Node content) {
        Label arrow = styledLabel("▼", "tp-collapse-arrow");
        Label headerLabel = styledLabel(title, "tp-section-header");
        HBox header = new HBox(4, arrow, headerLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("tp-collapsible-header");
        header.setCursor(Cursor.HAND);
        header.setOnMouseClicked(event -> setExpanded(content, arrow, !content.isVisible()));

        VBox section = new VBox(4, header, content);
        section.getStyleClass().add(styleClass);
        section.setPadding(new Insets(8, 10, 8, 10));
        return new CollapsibleSection(section, () -> setExpanded(content, arrow, false));
    }

    private static void setExpanded(Node content, Label arrow, boolean expanded) {
        content.setVisible(expanded);
        content.setManaged(expanded);
        arrow.setText(expanded ? "▼" : "▶");
    }

    private static TextFlow createTextFlow(String styleClass) {
        Text text = new Text();
        text.getStyleClass().add(styleClass);
        TextFlow flow = new TextFlow(text);
        flow.setMaxWidth(250);
        return flow;
    }

    private static VBox paddedContent(Node child) {
        VBox content = new VBox(4, child);
        content.setPadding(new Insets(4, 0, 0, 0));
        return content;
    }

    private static Label styledLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String statusSlug(String status) {
        if (status == null) return "ok";
        String lower = status.toLowerCase();
        if (lower.contains("ok") || lower.contains("完成") || lower.contains("success")) {
            return "ok";
        }
        if (lower.contains("error") || lower.contains("失败") || lower.contains("fail")) {
            return "error";
        }
        return "running";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void collapse(Runnable action) {
        if (action != null) action.run();
    }

    private record CollapsibleSection(VBox container, Runnable collapse) {
    }

    private record PipelineStageRow(
            VBox container, Label dot, Label statusBadge, Label detailLabel) {

        void update(String status, String detail) {
            String state = switch (status == null ? "running" : status.toLowerCase()) {
                case "running", "done", "skipped", "error" ->
                        status == null ? "running" : status.toLowerCase();
                default -> "running";
            };
            dot.getStyleClass().removeAll(
                    "tp-pipeline-dot-idle", "tp-pipeline-dot-running",
                    "tp-pipeline-dot-done", "tp-pipeline-dot-skipped",
                    "tp-pipeline-dot-error");
            dot.getStyleClass().add("tp-pipeline-dot-" + state);
            statusBadge.getStyleClass().removeAll(
                    "tp-pipeline-status-idle", "tp-pipeline-status-running",
                    "tp-pipeline-status-done", "tp-pipeline-status-skipped",
                    "tp-pipeline-status-error");
            statusBadge.getStyleClass().add("tp-pipeline-status-" + state);
            statusBadge.setText(switch (state) {
                case "running" -> "进行中";
                case "done" -> "完成";
                case "skipped" -> "跳过";
                case "error" -> "失败";
                default -> "—";
            });
            boolean hasDetail = detail != null && !detail.isBlank();
            detailLabel.setText(hasDetail ? detail : "");
            detailLabel.setVisible(hasDetail);
            detailLabel.setManaged(hasDetail);
        }
    }
}
