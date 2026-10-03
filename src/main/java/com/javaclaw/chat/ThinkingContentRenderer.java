package com.javaclaw.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.desktop.api.DesktopActionResult;
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
    private static final int MAX_TRACKED_INVOCATIONS = 256;
    private static final int MAX_LABEL_LENGTH = 34;
    private static final int MAX_DETAIL_LENGTH = 16_000;
    private static final int DETAIL_PREVIEW_LENGTH = 360;
    private static final String LOOP_OVERVIEW_ID = "loop-overview";

    private final VBox sections;
    private final Map<String, PipelineStageRow> pipelineRows = new LinkedHashMap<>();
    private final Map<String, AgentRow> agentRows = new LinkedHashMap<>();
    private final Map<String, ToolRow> toolRows = new LinkedHashMap<>();
    private final Map<String, ToolRow> toolInvocations = new LinkedHashMap<>();
    private final Map<String, ToolRow> latestLegacyTools = new LinkedHashMap<>();
    private long toolRowSequence;

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
        toolInvocations.clear();
        latestLegacyTools.clear();
        toolRowSequence = 0;
        pipelineBox = null;
        activityBox = null;
        thinkingDetail = null;
        planDetail = null;
    }

    enum StreamEnd { COMPLETED, CANCELLED, FAILED }
    enum AgentState { THINKING, REPLYING, COMPLETED, STOPPED, FAILED }
    enum ToolState { RUNNING, WAITING, SUCCEEDED, FAILED, STOPPED, UNCERTAIN, REOBSERVE, UNKNOWN }
    enum StageState { RUNNING, DONE, SKIPPED, STOPPED, ERROR }

    void finish(StreamEnd end) {
        AgentState agentState = switch (end) {
            case COMPLETED -> AgentState.COMPLETED;
            case CANCELLED -> AgentState.STOPPED;
            case FAILED -> AgentState.FAILED;
        };
        for (AgentRow row : agentRows.values()) {
            if (row.running()) row.setStatus(agentState, agentStatusText(agentState));
        }
        StageState stageState = switch (end) {
            case COMPLETED -> StageState.DONE;
            case CANCELLED -> StageState.STOPPED;
            case FAILED -> StageState.ERROR;
        };
        for (PipelineStageRow row : pipelineRows.values()) {
            if (row.running()) row.update(stageState, null);
        }
        ToolState toolState = switch (end) {
            case COMPLETED -> ToolState.SUCCEEDED;
            case CANCELLED -> ToolState.STOPPED;
            case FAILED -> ToolState.FAILED;
        };
        for (ToolRow row : toolRows.values()) {
            if (!row.terminal()) row.setStatus(toolState);
        }
    }

    void recordPipelineProgress(String stageId, String label, StageState status, String detail) {
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
        StageState state = status.decision() == Decision.DONE ? StageState.DONE
                : status.decision() == Decision.STOP ? StageState.STOPPED : StageState.RUNNING;
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
        row.setStatus(AgentState.THINKING, "进行中");
        row.appendThinking(thinking);
    }

    void markSubAgentReplying(String agentName) {
        agentRow(agentName, false).setStatus(AgentState.REPLYING, "返回结果中…");
    }

    void appendSubAgentReply(String agentName, String chunk) {
        appendSubAgentReply(agentName, chunk, null);
    }

    void appendSubAgentReply(
            String agentName, String chunk, ChatInlineImageRenderer inlineImages) {
        AgentRow row = agentRow(agentName, false);
        row.setStatus(AgentState.REPLYING, "返回结果中…");
        row.appendReply(chunk);
        if (inlineImages != null) row.detail.displayImages(inlineImages, chunk);
    }

    void markSubAgentResult(String agentName, String briefResult) {
        AgentRow row = agentRow(agentName, false);
        row.appendResult(briefResult);
        row.setStatus(AgentState.COMPLETED, "已完成");
    }

    void completeSubAgentIfPresent(String agentName) {
        completeSubAgentIfPresent(agentName, ToolExecutionStatus.SUCCEEDED);
    }

    void completeSubAgentIfPresent(String agentName, ToolExecutionStatus executionStatus) {
        AgentRow row = agentRows.get(agentName == null ? "" : agentName);
        if (row == null) return;
        switch (resultKind(executionStatus)) {
            case SUCCEEDED -> row.setStatus(AgentState.COMPLETED, "已完成");
            case FAILED -> row.setStatus(AgentState.FAILED, "失败");
            case PENDING -> row.setStatus(AgentState.STOPPED, "等待授权");
            case UNCERTAIN -> row.setStatus(AgentState.STOPPED, "结果待核验");
            case REOBSERVE -> row.setStatus(AgentState.STOPPED, "等待重新观察");
            case UNKNOWN -> row.setStatus(AgentState.STOPPED, "状态未知");
        }
    }

    void appendToolCall(String name, String input, ToolState status) {
        appendToolCall(name, "", input, status);
    }

    void appendToolCall(String name, String invocationId, String input, ToolState status) {
        ToolRow row = toolRow(name, invocationId);
        row.appendInput(name, invocationId, input);
        row.setStatus(status == null ? ToolState.RUNNING : status);
    }

    void appendToolResult(String name, String result) {
        appendToolResult(name, result, null);
    }

    void appendToolResult(String name, String result, ChatInlineImageRenderer inlineImages) {
        appendToolResult(name, "", result, null, ToolExecutionStatus.UNKNOWN, inlineImages);
    }

    void appendToolResult(String name, String invocationId, String result,
            JsonNode output, ToolExecutionStatus executionStatus,
            ChatInlineImageRenderer inlineImages) {
        ToolRow row = toolRow(name, invocationId);
        row.appendResult(name, invocationId, result, output, executionStatus);
        if (inlineImages != null) row.detail.displayImages(inlineImages, result);
        if (!row.internal()) {
            switch (resultKind(executionStatus)) {
                case FAILED -> row.setStatus(ToolState.FAILED);
                case UNCERTAIN -> row.setOutcome("调用结果不确定", ToolState.UNCERTAIN);
                case REOBSERVE -> row.setOutcome("未执行·待观察", ToolState.REOBSERVE);
                case PENDING -> row.setStatus(ToolState.WAITING);
                case SUCCEEDED -> row.setOutcome("调用已返回", ToolState.SUCCEEDED);
                case UNKNOWN -> row.setOutcome("调用状态未知", ToolState.UNKNOWN);
            }
        }
    }

    enum ResultKind { FAILED, UNCERTAIN, REOBSERVE, PENDING, SUCCEEDED, UNKNOWN }
    private enum ReceiptDelivery { NOT_SENT, SENT, MAYBE_SENT, UNSPECIFIED }

    static ResultKind resultKind(ToolExecutionStatus status) {
        if (status == null) return ResultKind.UNKNOWN;
        return switch (status) {
            case SUCCEEDED -> ResultKind.SUCCEEDED;
            case FAILED, TIMED_OUT -> ResultKind.FAILED;
            case PENDING -> ResultKind.PENDING;
            case UNCERTAIN -> ResultKind.UNCERTAIN;
            case REOBSERVE -> ResultKind.REOBSERVE;
            case UNKNOWN -> ResultKind.UNKNOWN;
        };
    }

    /** The effect badge is independent from the invocation's return status. */
    static String receiptEffectLabel(EffectReceiptV1.Status status, JsonNode metadata) {
        if (status == null) return null;
        if (status == EffectReceiptV1.Status.VERIFIED) return "目标已验证";
        ReceiptDelivery delivery = receiptDelivery(metadata);
        if (delivery == ReceiptDelivery.UNSPECIFIED && metadata != null) {
            // Historical desktop receipts predate the explicit delivery field.
            DesktopActionResult.Status desktop = desktopStatus(metadata.path("desktopStatus"));
            boolean attempted = metadata.path("dispatchAttempted").asBoolean(false);
            delivery = switch (desktop) {
                case VERIFIED, ACCEPTED -> ReceiptDelivery.SENT;
                case UNKNOWN -> ReceiptDelivery.MAYBE_SENT;
                case UNSUPPORTED, STALE_FRAME, DENIED ->
                        attempted ? ReceiptDelivery.MAYBE_SENT : ReceiptDelivery.NOT_SENT;
                case FAILED -> attempted ? ReceiptDelivery.MAYBE_SENT
                        : ReceiptDelivery.UNSPECIFIED;
                case null -> ReceiptDelivery.UNSPECIFIED;
            };
        }
        return switch (delivery) {
            case NOT_SENT -> "输入未派发";
            case SENT -> "输入已派发·效果待核验";
            case MAYBE_SENT -> "输入可能已派发·效果待核验";
            case UNSPECIFIED -> switch (status) {
                case FAILED -> "效果未获确认";
                case UNKNOWN -> "效果未知";
                case ACCEPTED -> "已受理·效果待核验";
                case OBSERVED -> "观察已记录";
                case VERIFIED -> "目标已验证";
            };
        };
    }

    private static ReceiptDelivery receiptDelivery(JsonNode metadata) {
        if (metadata == null || !metadata.path("delivery").isTextual())
            return ReceiptDelivery.UNSPECIFIED;
        try { return ReceiptDelivery.valueOf(metadata.path("delivery").asText()); }
        catch (IllegalArgumentException invalid) { return ReceiptDelivery.UNSPECIFIED; }
    }

    private static DesktopActionResult.Status desktopStatus(JsonNode value) {
        if (!value.isTextual()) return null;
        try { return DesktopActionResult.Status.valueOf(value.asText()); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    boolean updateToolReceipt(String name, EffectReceiptV1.Status receiptStatus) {
        return updateToolReceipt(name, "", receiptStatus, "");
    }

    boolean updateToolReceipt(String name, String invocationId,
            EffectReceiptV1.Status receiptStatus, String reason) {
        return updateToolReceipt(name, invocationId, receiptStatus, reason, null);
    }

    boolean updateToolReceipt(String name, String invocationId,
            EffectReceiptV1.Status receiptStatus, String reason, JsonNode metadata) {
        if (name == null || name.isBlank() || receiptStatus == null) return false;
        ToolRow row = invocationId == null || invocationId.isBlank()
                ? null : toolInvocations.get(invocationId);
        if (row == null && (invocationId == null || invocationId.isBlank())) {
            // Old callers produced name-only events. Associate a receipt only when
            // exactly one such row exists, never guess among repeated names.
            var legacy = toolRows.values().stream()
                    .filter(candidate -> candidate.name.equals(name)
                            && candidate.invocationId.isBlank()).toList();
            if (legacy.size() == 1) row = legacy.getFirst();
        }
        if (row == null || !row.internal() && !row.name.equals(name)) return false;
        return row.appendReceipt(receiptStatus, reason, metadata);
    }

    void appendToolFailure(String name, String detail) {
        appendToolFailure(name, "", detail);
    }

    void appendToolFailure(String name, String invocationId, String detail) {
        ToolRow row = toolRow(name, invocationId);
        row.appendFailure(invocationId, detail);
        row.setStatus(ToolState.FAILED);
    }

    private void updateStage(String stageId, String label, StageState status, String detail) {
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

    private ToolRow toolRow(String name, String invocationId) {
        String displayName = name == null || name.isBlank() ? "工具" : name;
        String id = invocationId == null ? "" : invocationId;
        ToolRow known = id.isBlank() ? null : toolInvocations.get(id);
        if (known != null) return known;
        boolean internal = "framework_tool_catalog".equals(displayName)
                || displayName.startsWith("framework_context_");
        if (internal) {
            ToolRow grouped = toolRows.get("internal:tool-preparation");
            if (grouped != null) {
                if (!id.isBlank()) rememberInvocation(id, grouped);
                return grouped;
            }
        } else if (id.isBlank()) {
            ToolRow legacy = latestLegacyTools.get(displayName);
            if (legacy != null && !legacy.terminal()) return legacy;
        }
        ensureActivityBox();
        Label bolt = styledLabel("⚡", "tp-tool-bolt");
        Label title = namedLabel(internal ? "工具准备" : displayName, "工具", "tp-tool-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = styledLabel("进行中", "tp-tool-status");
        Label effect = styledLabel("", "tp-tool-effect-status");
        effect.setVisible(false);
        effect.setManaged(false);
        HBox header = new HBox(6, bolt, title, spacer, state, effect);
        header.setAlignment(Pos.CENTER_LEFT);
        DetailBlock detail = new DetailBlock(internal);
        VBox container = new VBox(4, header, detail.root());
        container.getStyleClass().add("tp-tool-row");
        container.setPadding(new Insets(6, 8, 6, 8));
        ToolRow row = new ToolRow(displayName, id, internal, container, state, effect, detail);
        toolRows.put(internal ? "internal:tool-preparation" : "tool:" + (++toolRowSequence), row);
        if (!id.isBlank()) rememberInvocation(id, row);
        else latestLegacyTools.put(displayName, row);
        addActivity(container);
        return row;
    }

    private void rememberInvocation(String id, ToolRow row) {
        toolInvocations.put(id, row);
        while (toolInvocations.size() > MAX_TRACKED_INVOCATIONS) {
            toolInvocations.remove(toolInvocations.keySet().iterator().next());
        }
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
            toolInvocations.values().removeIf(tool -> tool.container() == oldest);
            latestLegacyTools.values().removeIf(tool -> tool.container() == oldest);
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

    private static String agentStatusText(AgentState state) {
        return switch (state) {
            case THINKING -> "进行中";
            case REPLYING -> "返回结果中…";
            case COMPLETED -> "已完成";
            case STOPPED -> "已停止";
            case FAILED -> "失败";
        };
    }

    private static final class DetailBlock {
        private final VBox root;
        private final Label text;
        private final Label toggle;
        private final StringBuilder content = new StringBuilder();
        private final Set<String> displayedImagePaths = new HashSet<>();
        private boolean expanded;
        private final boolean collapsedByDefault;
        private boolean omittedPrefix;
        private String lastLine;

        private DetailBlock() {
            this(false);
        }

        private DetailBlock(boolean collapsedByDefault) {
            this.collapsedByDefault = collapsedByDefault;
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
            boolean collapsed = collapsedByDefault && !expanded;
            text.setVisible(!collapsed);
            text.setManaged(!collapsed);
            toggle.setText(expanded ? "收起记录" : collapsedByDefault ? "展开记录" : "展开全文");
            toggle.setVisible(collapsedByDefault || longBody);
            toggle.setManaged(collapsedByDefault || longBody);
            text.setCursor(longBody && !collapsed ? Cursor.HAND : Cursor.DEFAULT);
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
        private AgentState state = AgentState.THINKING;

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

        void setStatus(AgentState state, String displayText) {
            this.state = Objects.requireNonNull(state);
            status.setText(displayText);
            status.getStyleClass().removeAll("agent-status-thinking", "agent-status-done",
                    "agent-status-failed", "agent-status-stopped");
            status.getStyleClass().add(switch (state) {
                case THINKING, REPLYING -> "agent-status-thinking";
                case COMPLETED -> "agent-status-done";
                case STOPPED -> "agent-status-stopped";
                case FAILED -> "agent-status-failed";
            });
        }

        boolean running() {
            return state == AgentState.THINKING || state == AgentState.REPLYING;
        }
    }

    private static final class ToolRow {
        private final String name;
        private final String invocationId;
        private final boolean internal;
        private final VBox container;
        private final Label status;
        private final Label effect;
        private final DetailBlock detail;
        private String lastInput;
        private boolean terminal;
        private boolean receiptApplied;
        private int internalCalls;
        private final Map<String, Integer> internalOrdinals = new LinkedHashMap<>();

        private ToolRow(String name, String invocationId, boolean internal,
                VBox container, Label status, Label effect, DetailBlock detail) {
            this.name = name;
            this.invocationId = invocationId;
            this.internal = internal;
            this.container = container;
            this.status = status;
            this.effect = effect;
            this.detail = detail;
        }

        VBox container() { return container; }
        boolean terminal() { return terminal; }
        boolean internal() { return internal; }
        boolean receiptApplied() { return receiptApplied; }

        void appendInput(String toolName, String invocationId, String input) {
            if (internal) {
                int ordinal = internalOrdinal(invocationId, true);
                detail.appendLine("第 " + ordinal + " 次 · " + toolName
                        + (input == null || input.isBlank() ? "" : " · 输入：" + input));
                return;
            }
            if (input == null || input.isBlank() || input.equals(lastInput)) return;
            detail.appendLine("输入：" + input);
            lastInput = input;
        }

        void appendResult(String toolName, String invocationId, String result,
                JsonNode output, ToolExecutionStatus executionStatus) {
            if (internal) {
                int ordinal = internalOrdinal(invocationId, false);
                String summary = "framework_tool_catalog".equals(toolName)
                        ? catalogSummary(output) : "上下文资料已返回";
                if (summary == null) {
                    summary = result == null || result.isBlank() ? "目录已返回" : "目录已返回结果";
                }
                detail.appendLine("第 " + ordinal + " 次结果：" + summary
                        + (result == null || result.isBlank() ? "" : " · " + result));
                setOutcome(summary, executionStatus == ToolExecutionStatus.SUCCEEDED
                        ? ToolState.SUCCEEDED : ToolState.UNKNOWN);
            } else if (result != null && !result.isBlank()) {
                detail.appendLine("结果：" + result);
            }
        }

        private static String catalogSummary(JsonNode output) {
            if (output == null || !output.isObject()) return null;
            if (output.has("success") && !output.path("success").asBoolean()) {
                return "失败：" + output.path("error").asText("目录调用失败");
            }
            String action = output.path("action").asText("");
            if ("activate".equals(action) && output.path("success").asBoolean()) {
                return "已激活 " + output.path("activated").size() + " 项";
            }
            if ("list".equals(action)) {
                int listed = output.path("tools").isArray() ? output.path("tools").size() : 0;
                int total = output.path("total").asInt(listed);
                return "已列出 " + listed + " 项" + (total > listed ? "，共 " + total + " 项" : "");
            }
            return null;
        }

        void appendFailure(String invocationId, String failure) {
            if (failure == null || failure.isBlank()) return;
            detail.appendLine(internal ? "第 " + internalOrdinal(invocationId, false)
                    + " 次失败：" + failure : "失败：" + failure);
        }

        private int internalOrdinal(String invocationId, boolean starting) {
            if (invocationId == null || invocationId.isBlank()) {
                if (starting || internalCalls == 0) internalCalls++;
                return internalCalls;
            }
            Integer existing = internalOrdinals.get(invocationId);
            if (existing != null) return existing;
            int ordinal = ++internalCalls;
            internalOrdinals.put(invocationId, ordinal);
            if (internalOrdinals.size() > 128) {
                internalOrdinals.remove(internalOrdinals.keySet().iterator().next());
            }
            return ordinal;
        }

        boolean appendReceipt(EffectReceiptV1.Status value, String reason, JsonNode metadata) {
            if (internal) return true;
            String label = receiptEffectLabel(value, metadata);
            if (label == null) return false;
            receiptApplied = true;
            effect.setText(label);
            effect.setVisible(true);
            effect.setManaged(true);
            effect.setTooltip(reason == null || reason.isBlank() ? null : new Tooltip(reason));
            detail.appendLine("效果证据：" + label
                    + (reason == null || reason.isBlank() ? "" : " · " + reason));
            // A receipt describes delivery and effect evidence. It cannot change the
            // outcome of the invocation itself, which is reported by ToolResult/ToolFailed.
            return true;
        }

        private void setOutcome(String text, ToolState outcome) {
            setStatus(outcome);
            status.setText(text);
            status.setMaxWidth(150);
            status.setTextOverrun(OverrunStyle.ELLIPSIS);
            status.setTooltip(new Tooltip(text));
        }

        void setStatus(ToolState state) {
            state = Objects.requireNonNull(state);
            terminal = switch (state) {
                case RUNNING, WAITING -> false;
                case SUCCEEDED, FAILED, STOPPED, UNCERTAIN, REOBSERVE, UNKNOWN -> true;
            };
            status.setText(switch (state) {
                case RUNNING -> "进行中";
                case WAITING -> "等待授权";
                case SUCCEEDED -> "完成";
                case FAILED -> "失败";
                case STOPPED -> "已停止";
                case UNCERTAIN -> "调用结果不确定";
                case REOBSERVE -> "未执行·待观察";
                case UNKNOWN -> "调用状态未知";
            });
            status.getStyleClass().removeAll(
                    "tp-tool-status-error", "tp-tool-status-ok",
                    "tp-tool-status-running", "tp-tool-status-waiting");
            status.getStyleClass().add(switch (state) {
                case RUNNING -> "tp-tool-status-running";
                case WAITING -> "tp-tool-status-waiting";
                case SUCCEEDED -> "tp-tool-status-ok";
                case FAILED, STOPPED, UNCERTAIN, REOBSERVE, UNKNOWN -> "tp-tool-status-error";
            });
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

        void update(StageState state, String summary) {
            state = state == null ? StageState.RUNNING : state;
            String token = state.name().toLowerCase(Locale.ROOT);
            running = state == StageState.RUNNING;
            dot.getStyleClass().removeAll("tp-pipeline-dot-idle", "tp-pipeline-dot-running",
                    "tp-pipeline-dot-done", "tp-pipeline-dot-skipped",
                    "tp-pipeline-dot-stopped", "tp-pipeline-dot-error");
            dot.getStyleClass().add("tp-pipeline-dot-" + token);
            status.getStyleClass().removeAll("tp-pipeline-status-idle", "tp-pipeline-status-running",
                    "tp-pipeline-status-done", "tp-pipeline-status-skipped",
                    "tp-pipeline-status-stopped", "tp-pipeline-status-error");
            status.getStyleClass().add("tp-pipeline-status-" + token);
            status.setText(switch (state) {
                case DONE -> "完成";
                case SKIPPED -> "跳过";
                case STOPPED -> "已停止";
                case ERROR -> "失败";
                case RUNNING -> "进行中";
            });
            if (summary != null && !summary.isBlank()) {
                detail.set(summary);
            } else if (!running) {
                detail.set(null);
            }
        }
    }
}
