package com.javaclaw.chat;

import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.util.SensitiveDataRedactor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Renders bounded, expandable execution details in the right-hand progress panel. */
final class ThinkingContentRenderer {

    private static final int MAX_STAGE_ROWS = 24;
    private static final int MAX_ACTIVITY_ROWS = 32;
    private static final int MAX_LABEL_LENGTH = 34;
    private static final int MAX_DETAIL_LENGTH = 16_000;
    private static final int DETAIL_PREVIEW_LENGTH = 360;
    private static final String LOOP_OVERVIEW_ID = "loop-overview";

    private final VBox sections;
    private final Map<String, PipelineStageRow> pipelineRows = new LinkedHashMap<>();
    private final Map<String, AgentRow> agentRows = new LinkedHashMap<>();
    private final Map<String, ToolRow> toolRows = new LinkedHashMap<>();

    private VBox pipelineBox;
    private VBox activityBox;
    private DetailBlock thinkingDetail;
    private DetailBlock planDetail;

    ThinkingContentRenderer(VBox sections) {
        this.sections = Objects.requireNonNull(sections, "sections");
    }

    void clear() {
        sections.getChildren().clear();
        pipelineRows.clear();
        agentRows.clear();
        toolRows.clear();
        pipelineBox = null;
        activityBox = null;
        thinkingDetail = null;
        planDetail = null;
    }

    void finish(String agentStatusText) {
        for (AgentRow row : agentRows.values()) {
            if (row.running()) row.setStatus(agentStatusText, false);
        }
        String terminal = "失败".equals(agentStatusText) ? "error"
                : "已停止".equals(agentStatusText) ? "stopped" : "done";
        for (PipelineStageRow row : pipelineRows.values()) {
            if (row.running()) row.update(terminal, null);
        }
        for (ToolRow row : toolRows.values()) {
            if (!row.terminal()) row.setStatus(terminal);
        }
    }

    void recordPipelineProgress(String stageId, String label, String status, String detail) {
        Objects.requireNonNull(stageId, "stageId");
        updateStage(stageId, label, status, detail);
    }

    void recordLoopStatus(LoopStatus status) {
        if (status == null) return;
        int iteration = Math.max(1, status.iteration());
        StringBuilder summary = new StringBuilder("第 ").append(iteration).append(" 轮");
        if (status.total() > 0) {
            int satisfied = Math.max(0, Math.min(status.satisfied(), status.total()));
            summary.append(" · 已满足 ").append(satisfied).append('/')
                    .append(status.total()).append(" 项");
        }
        if (status.decision() == Decision.CONTINUE && status.nextDelaySeconds() > 0) {
            summary.append(" · ").append(status.nextDelaySeconds()).append(" 秒后继续");
        }
        String state = status.decision() == Decision.DONE ? "done"
                : status.decision() == Decision.STOP ? "stopped" : "running";
        // Loop reasons may contain a full answer. Keep its structured progress here.
        updateStage(LOOP_OVERVIEW_ID, "循环进度", state, summary.toString());
    }

    void appendThinking(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        if (thinkingDetail == null) {
            thinkingDetail = detailSection("思考过程", "tp-thinking-section");
        }
        thinkingDetail.append(chunk);
    }

    void updatePlan(String hint) {
        if (hint == null || hint.isBlank()) return;
        if (planDetail == null) {
            planDetail = detailSection("执行规划", "tp-plan-section");
        }
        planDetail.appendLine(hint);
    }

    void appendSubAgentThinking(String agentName, String thinking) {
        AgentRow row = agentRow(agentName, true);
        row.setStatus("进行中", true);
        row.appendThinking(thinking);
    }

    void markSubAgentReplying(String agentName) {
        agentRow(agentName, false).setStatus("返回结果中…", true);
    }

    void appendSubAgentReply(String agentName, String chunk) {
        appendSubAgentReply(agentName, chunk, null);
    }

    void appendSubAgentReply(
            String agentName, String chunk, ChatInlineImageRenderer inlineImages) {
        AgentRow row = agentRow(agentName, false);
        row.setStatus("返回结果中…", true);
        row.appendReply(chunk);
        if (inlineImages != null) row.detail.displayImages(inlineImages, chunk);
    }

    void markSubAgentResult(String agentName, String briefResult) {
        AgentRow row = agentRow(agentName, false);
        row.appendResult(briefResult);
        row.setStatus("已完成", false);
    }

    void completeSubAgentIfPresent(String agentName) {
        AgentRow row = agentRows.get(agentName == null ? "" : agentName);
        if (row != null) row.setStatus("已完成", false);
    }

    void appendToolCall(String name, String input, String status) {
        ToolRow row = toolRow(name);
        row.appendInput(input);
        row.setStatus(status);
    }

    void appendToolResult(String name, String result) {
        appendToolResult(name, result, null);
    }

    void appendToolResult(String name, String result, ChatInlineImageRenderer inlineImages) {
        ToolRow row = toolRow(name);
        row.appendResult(result);
        if (inlineImages != null) row.detail.displayImages(inlineImages, result);
        row.setStatus("ok");
    }

    void appendToolFailure(String name, String detail) {
        ToolRow row = toolRow(name);
        row.appendFailure(detail);
        row.setStatus("error");
    }

    private void updateStage(String stageId, String label, String status, String detail) {
        ensurePipelineBox();
        PipelineStageRow row = pipelineRows.get(stageId);
        if (row == null) {
            if (pipelineRows.size() == MAX_STAGE_ROWS) {
                String oldest = pipelineRows.keySet().iterator().next();
                pipelineBox.getChildren().remove(pipelineRows.remove(oldest).container());
            }
            row = createPipelineRow(stageId, label);
            pipelineRows.put(stageId, row);
            pipelineBox.getChildren().add(row.container());
        }
        row.update(status, detail);
    }

    private void ensurePipelineBox() {
        if (pipelineBox != null) return;
        pipelineBox = new VBox(5);
        VBox section = new VBox(7, styledLabel("阶段进度", "tp-section-header"), pipelineBox);
        section.getStyleClass().add("tp-pipeline-section");
        section.setPadding(new Insets(10));
        sections.getChildren().add(section);
    }

    private PipelineStageRow createPipelineRow(String stageId, String label) {
        Label dot = styledLabel("●", "tp-pipeline-dot");
        Label name = namedLabel(label, stageId, "tp-pipeline-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = styledLabel("待命", "tp-pipeline-status");
        HBox header = new HBox(6, dot, name, spacer, state);
        header.setAlignment(Pos.CENTER_LEFT);
        DetailBlock detail = new DetailBlock();
        VBox container = new VBox(3, header, detail.root());
        container.getStyleClass().add("tp-pipeline-row");
        container.setPadding(new Insets(6));
        return new PipelineStageRow(container, dot, state, detail);
    }

    private DetailBlock detailSection(String title, String styleClass) {
        DetailBlock detail = new DetailBlock();
        VBox section = new VBox(6, styledLabel(title, "tp-section-header"), detail.root());
        section.getStyleClass().add(styleClass);
        section.setPadding(new Insets(10));
        sections.getChildren().add(section);
        return detail;
    }

    private AgentRow agentRow(String name, boolean newInvocation) {
        String key = name == null ? "" : name;
        AgentRow existing = agentRows.get(key);
        if (existing != null && !(newInvocation && !existing.running())) return existing;
        ensureActivityBox();
        Label title = namedLabel(name, "智能体", "tp-agent-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = styledLabel("进行中", "tp-agent-status");
        HBox header = new HBox(6, title, spacer, state);
        header.setAlignment(Pos.CENTER_LEFT);
        DetailBlock detail = new DetailBlock();
        VBox container = new VBox(4, header, detail.root());
        container.getStyleClass().add("tp-agent-card");
        container.setPadding(new Insets(7, 8, 7, 8));
        AgentRow row = new AgentRow(container, state, detail);
        agentRows.put(key, row);
        addActivity(container);
        return row;
    }

    private ToolRow toolRow(String name) {
        String key = name == null || name.isBlank() ? "工具" : name;
        ToolRow existing = toolRows.get(key);
        if (existing != null && !existing.terminal()) return existing;
        ensureActivityBox();
        Label bolt = styledLabel("⚡", "tp-tool-bolt");
        Label title = namedLabel(key, "工具", "tp-tool-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = styledLabel("进行中", "tp-tool-status");
        HBox header = new HBox(6, bolt, title, spacer, state);
        header.setAlignment(Pos.CENTER_LEFT);
        DetailBlock detail = new DetailBlock();
        VBox container = new VBox(4, header, detail.root());
        container.getStyleClass().add("tp-tool-row");
        container.setPadding(new Insets(6, 8, 6, 8));
        ToolRow row = new ToolRow(container, state, detail);
        toolRows.put(key, row);
        addActivity(container);
        return row;
    }

    private void ensureActivityBox() {
        if (activityBox != null) return;
        activityBox = new VBox(6);
        VBox section = new VBox(7, styledLabel("执行活动", "tp-section-header"), activityBox);
        section.getStyleClass().add("tp-agents-section");
        section.setPadding(new Insets(10));
        sections.getChildren().add(section);
    }

    private void addActivity(Node row) {
        if (activityBox.getChildren().size() == MAX_ACTIVITY_ROWS) {
            Node oldest = activityBox.getChildren().removeFirst();
            agentRows.values().removeIf(agent -> agent.container() == oldest);
            toolRows.values().removeIf(tool -> tool.container() == oldest);
        }
        activityBox.getChildren().add(row);
    }

    private static Label styledLabel(String value, String styleClass) {
        Label label = new Label(value);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Label namedLabel(String value, String fallback, String styleClass) {
        String full = value == null || value.isBlank() ? fallback : value.strip();
        String compact = full.replaceAll("\\s+", " ");
        Label label = styledLabel(compact.length() <= MAX_LABEL_LENGTH ? compact
                : compact.substring(0, MAX_LABEL_LENGTH - 1) + "…", styleClass);
        label.setMinWidth(0);
        label.setMaxWidth(135);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        label.setTooltip(new Tooltip(full));
        return label;
    }

    private static final class DetailBlock {
        private final VBox root;
        private final Label text;
        private final Label toggle;
        private final StringBuilder content = new StringBuilder();
        private final Set<String> displayedImagePaths = new HashSet<>();
        private boolean expanded;
        private boolean omittedPrefix;
        private String lastLine;

        private DetailBlock() {
            text = styledLabel("", "tp-detail-text");
            text.setWrapText(true);
            text.setMinWidth(0);
            text.setMaxWidth(Double.MAX_VALUE);
            toggle = styledLabel("展开全文", "tp-detail-toggle");
            toggle.setCursor(Cursor.HAND);
            toggle.setOnMouseClicked(event -> switchExpanded());
            text.setOnMouseClicked(event -> {
                if (toggle.isVisible()) switchExpanded();
            });
            root = new VBox(4, text, toggle);
            root.setVisible(false);
            root.setManaged(false);
        }

        VBox root() { return root; }

        void displayImages(ChatInlineImageRenderer inlineImages, String original) {
            inlineImages.displayInDetail(original, root, displayedImagePaths);
            // Also scan retained text when an image path spans two streaming chunks.
            inlineImages.displayInDetail(content.toString(), root, displayedImagePaths);
        }

        void set(String value) {
            content.setLength(0);
            omittedPrefix = false;
            lastLine = null;
            if (value == null || value.isEmpty()) refresh();
            else append(value);
        }

        void appendLine(String value) {
            if (value == null || value.isBlank() || value.equals(lastLine)) return;
            if (!content.isEmpty()) append("\n");
            append(value);
            lastLine = value.length() <= 512 ? value : null;
        }

        void append(String value) {
            if (value == null || value.isEmpty()) return;
            if (value.length() >= MAX_DETAIL_LENGTH) {
                omittedPrefix = omittedPrefix || !content.isEmpty()
                        || value.length() > MAX_DETAIL_LENGTH;
                content.setLength(0);
                content.append(value, value.length() - MAX_DETAIL_LENGTH, value.length());
            } else {
                int overflow = content.length() + value.length() - MAX_DETAIL_LENGTH;
                if (overflow > 0) {
                    content.delete(0, overflow);
                    omittedPrefix = true;
                }
                content.append(value);
            }
            refresh();
        }

        private void switchExpanded() {
            expanded = !expanded;
            refresh();
        }

        private void refresh() {
            String body = SensitiveDataRedactor.redactText(
                    (omittedPrefix ? "… 前文已省略\n" : "") + content);
            boolean longBody = body.length() > DETAIL_PREVIEW_LENGTH;
            text.setText(expanded || !longBody ? body
                    : "…" + body.substring(body.length() - DETAIL_PREVIEW_LENGTH));
            toggle.setText(expanded ? "收起" : "展开全文");
            toggle.setVisible(longBody);
            toggle.setManaged(longBody);
            text.setCursor(longBody ? Cursor.HAND : Cursor.DEFAULT);
            root.setVisible(!content.isEmpty());
            root.setManaged(!content.isEmpty());
        }
    }

    private static final class AgentRow {
        private final VBox container;
        private final Label status;
        private final DetailBlock detail;
        private boolean thinkingStarted;
        private boolean replyStarted;
        private boolean resultStarted;

        private AgentRow(VBox container, Label status, DetailBlock detail) {
            this.container = container;
            this.status = status;
            this.detail = detail;
        }

        VBox container() { return container; }

        void appendThinking(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            if (!thinkingStarted) {
                detail.append("思考：\n");
                thinkingStarted = true;
            }
            detail.append(chunk);
        }

        void appendReply(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            if (!replyStarted) {
                detail.append("\n回复：\n");
                replyStarted = true;
            }
            detail.append(chunk);
        }

        void appendResult(String result) {
            if (result == null || result.isBlank() || resultStarted) return;
            detail.append("\n结果：\n" + result);
            resultStarted = true;
        }

        void setStatus(String value, boolean running) {
            status.setText(value);
            status.getStyleClass().removeAll("agent-status-thinking", "agent-status-done",
                    "agent-status-failed", "agent-status-stopped");
            status.getStyleClass().add(running ? "agent-status-thinking"
                    : "失败".equals(value) ? "agent-status-failed"
                    : "已停止".equals(value) ? "agent-status-stopped" : "agent-status-done");
        }

        boolean running() {
            return status.getStyleClass().contains("agent-status-thinking");
        }
    }

    private static final class ToolRow {
        private final VBox container;
        private final Label status;
        private final DetailBlock detail;
        private String lastInput;
        private boolean terminal;

        private ToolRow(VBox container, Label status, DetailBlock detail) {
            this.container = container;
            this.status = status;
            this.detail = detail;
        }

        VBox container() { return container; }
        boolean terminal() { return terminal; }

        void appendInput(String input) {
            if (input == null || input.isBlank() || input.equals(lastInput)) return;
            detail.appendLine("输入：" + input);
            lastInput = input;
        }

        void appendResult(String result) {
            if (result != null && !result.isBlank()) detail.appendLine("结果：" + result);
        }

        void appendFailure(String failure) {
            if (failure != null && !failure.isBlank()) detail.appendLine("失败：" + failure);
        }

        void setStatus(String value) {
            String normalized = value == null ? "running" : value.toLowerCase(Locale.ROOT);
            boolean failed = normalized.contains("error") || normalized.contains("fail")
                    || normalized.contains("失败");
            boolean complete = normalized.contains("ok") || normalized.contains("success")
                    || normalized.contains("done") || normalized.contains("complet")
                    || normalized.contains("完成");
            boolean waiting = normalized.contains("wait") || normalized.contains("授权");
            boolean stopped = normalized.contains("stop") || normalized.contains("停止");
            terminal = failed || complete || stopped;
            status.setText(failed ? "失败" : complete ? "完成" : waiting ? "等待授权"
                    : stopped ? "已停止" : "进行中");
            status.getStyleClass().removeAll(
                    "tp-tool-status-error", "tp-tool-status-ok",
                    "tp-tool-status-running", "tp-tool-status-waiting");
            status.getStyleClass().add(failed ? "tp-tool-status-error"
                    : complete ? "tp-tool-status-ok"
                    : waiting ? "tp-tool-status-waiting" : "tp-tool-status-running");
        }
    }

    private static final class PipelineStageRow {
        private final VBox container;
        private final Label dot;
        private final Label status;
        private final DetailBlock detail;
        private boolean running;

        private PipelineStageRow(VBox container, Label dot, Label status, DetailBlock detail) {
            this.container = container;
            this.dot = dot;
            this.status = status;
            this.detail = detail;
        }

        VBox container() { return container; }
        boolean running() { return running; }

        void update(String value, String summary) {
            String state = switch (value == null ? "running" : value.toLowerCase(Locale.ROOT)) {
                case "running", "done", "skipped", "stopped", "error" ->
                        value == null ? "running" : value.toLowerCase(Locale.ROOT);
                default -> "running";
            };
            running = state.equals("running");
            dot.getStyleClass().removeAll("tp-pipeline-dot-idle", "tp-pipeline-dot-running",
                    "tp-pipeline-dot-done", "tp-pipeline-dot-skipped",
                    "tp-pipeline-dot-stopped", "tp-pipeline-dot-error");
            dot.getStyleClass().add("tp-pipeline-dot-" + state);
            status.getStyleClass().removeAll("tp-pipeline-status-idle", "tp-pipeline-status-running",
                    "tp-pipeline-status-done", "tp-pipeline-status-skipped",
                    "tp-pipeline-status-stopped", "tp-pipeline-status-error");
            status.getStyleClass().add("tp-pipeline-status-" + state);
            status.setText(switch (state) {
                case "done" -> "完成";
                case "skipped" -> "跳过";
                case "stopped" -> "已停止";
                case "error" -> "失败";
                default -> "进行中";
            });
            if (summary != null && !summary.isBlank()) {
                detail.set(summary);
            } else if (!running) {
                detail.set(null);
            }
        }
    }
}
