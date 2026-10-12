package com.javaclaw.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.chat.ThinkingPanelViewModel.PanelStatus;
import com.javaclaw.chat.ThinkingContentRenderer.StageState;
import com.javaclaw.chat.ThinkingContentRenderer.ToolState;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thinking Panel 的 FXML Controller。
 *
 * <p>所有入口都受 JavaFX Application Thread 约束。Controller 只把会话事件映射为
 * {@link ThinkingPanelViewModel} 状态和动态渲染命令；关闭后停止计时，重复关闭无副作用。</p>
 */
public final class ThinkingPanelController implements AutoCloseable {

    @FXML private VBox root;
    @FXML private VBox contentBox;
    @FXML private ScrollPane scrollPane;
    @FXML private Label statusTag;
    @FXML private Label elapsedLabel;
    @FXML private Label tokensInValue;
    @FXML private Label tokensOutValue;
    @FXML private Label costValue;
    @FXML private Label emptyHint;
    @FXML private VBox dynamicSectionsHost;

    private final ThinkingPanelViewModel viewModel;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ChangeListener<PanelStatus> statusTypeListener =
            (observable, previous, current) -> applyStatusStyle(current);
    private ThinkingContentRenderer renderer;
    private Timeline elapsedTicker;
    private long streamStartMillis;
    private boolean followLatest = true;
    private boolean settingScrollPosition;

    public ThinkingPanelController() {
        this(new ThinkingPanelViewModel());
    }

    ThinkingPanelController(ThinkingPanelViewModel viewModel) {
        this.viewModel = java.util.Objects.requireNonNull(viewModel, "viewModel");
    }

    @FXML
    private void initialize() {
        renderer = new ThinkingContentRenderer(dynamicSectionsHost);
        statusTag.textProperty().bind(viewModel.statusTextProperty());
        elapsedLabel.textProperty().bind(viewModel.elapsedProperty());
        tokensInValue.textProperty().bind(viewModel.tokensInProperty().asString());
        tokensOutValue.textProperty().bind(viewModel.tokensOutProperty().asString());
        costValue.textProperty().bind(viewModel.costProperty());
        emptyHint.visibleProperty().bind(viewModel.emptyProperty());
        emptyHint.managedProperty().bind(viewModel.emptyProperty());
        viewModel.statusTypeProperty().addListener(statusTypeListener);
        scrollPane.vvalueProperty().addListener((observable, previous, current) -> {
            var viewport = scrollPane.getViewportBounds();
            if (!settingScrollPosition && viewport != null
                    && contentBox.getHeight() > viewport.getHeight()) {
                followLatest = current.doubleValue() >= 0.95;
            }
        });
        contentBox.heightProperty().addListener((observable, previous, current) -> {
            if (!followLatest) return;
            settingScrollPosition = true;
            try { scrollPane.setVvalue(1.0); }
            finally { settingScrollPosition = false; }
        });
        applyStatusStyle(viewModel.statusTypeProperty().get());
    }

    public VBox getRoot() {
        return root;
    }

    /** 清空上一轮动态内容并开始计时。 */
    public void startNewStream() {
        ensureOpen();
        renderer.clear();
        followLatest = true;
        scrollPane.setVvalue(1.0);
        viewModel.setEmpty(false);
        setStatus(PanelStatus.THINKING, "思考中...");
        updateMetrics(0, 0, "¥0.00");
        streamStartMillis = System.currentTimeMillis();
        startElapsedTicker();
    }

    public void endStream() {
        endStream("处理完成", ThinkingContentRenderer.StreamEnd.COMPLETED);
    }

    /** Run settlement and task acceptance remain distinct in the progress panel. */
    void endStream(TaskResult result) {
        String taskStatus = result == null ? null : switch (result.outcome()) {
            case PARTIAL -> "未完成 · 待继续";
            case BLOCKED -> "受阻 · 待处理";
            case UNVERIFIED -> "运行结束 · 未核验";
            case VERIFIED_COMPLETE, DELIVERED, NOT_APPLICABLE -> null;
        };
        if (taskStatus == null) endStream();
        else endStream(taskStatus, ThinkingContentRenderer.StreamEnd.COMPLETED,
                result.outcome() == com.javaclaw.framework.api.TaskOutcome.UNVERIFIED
                        ? "本轮" + taskStatus : "本轮任务" + taskStatus);
    }

    public void endStreamCancelled() {
        endStream("已取消", ThinkingContentRenderer.StreamEnd.CANCELLED);
    }

    public void endStreamFailed() {
        endStream("失败", ThinkingContentRenderer.StreamEnd.FAILED);
    }

    private void endStream(String panelStatus, ThinkingContentRenderer.StreamEnd end) {
        endStream(panelStatus, end, null);
    }

    private void endStream(String panelStatus, ThinkingContentRenderer.StreamEnd end,
            String taskStatus) {
        if (closed.get()) return;
        setStatus(PanelStatus.IDLE, panelStatus);
        stopElapsedTicker();
        refreshElapsedLabel();
        renderer.finish(end, taskStatus);
    }

    public void reset() {
        if (closed.get()) return;
        stopElapsedTicker();
        renderer.clear();
        followLatest = true;
        viewModel.setEmpty(true);
        viewModel.setElapsed("0.0s");
        viewModel.setMetrics(0, 0, "¥0.00");
        viewModel.setStatus(PanelStatus.IDLE, "等待中");
    }

    void setStatus(PanelStatus type, String text) {
        if (closed.get()) return;
        viewModel.setStatus(type, text);
    }

    void recordPipelineProgress(
            String stageId, String label, StageState status, String detail) {
        ensureOpen();
        renderer.recordPipelineProgress(stageId, label, status, detail);
    }

    /** Show structured loop progress without copying its free-form reason into the panel. */
    public void recordLoopStatus(LoopStatus status) {
        ensureOpen();
        renderer.recordLoopStatus(status);
    }

    public void appendThinking(String chunk) {
        ensureOpen();
        setStatus(PanelStatus.THINKING, "思考中...");
        renderer.appendThinking(chunk);
    }

    public void updatePlan(String hint) {
        ensureOpen();
        setStatus(PanelStatus.PLANNING, "规划中...");
        renderer.updatePlan(hint);
    }

    public void appendSubAgentThinking(String agentName, String thinking) {
        ensureOpen();
        setStatus(PanelStatus.EXECUTING, "智能体执行中...");
        renderer.appendSubAgentThinking(agentName, thinking);
    }

    /** A reply stream is still in progress; only a ToolResult marks it complete. */
    public void markSubAgentReplying(String agentName) {
        ensureOpen();
        setStatus(PanelStatus.EXECUTING, "智能体返回结果中...");
        renderer.markSubAgentReplying(agentName);
    }

    public void appendSubAgentReply(String agentName, String chunk) {
        appendSubAgentReply(agentName, chunk, null);
    }

    void appendSubAgentReply(
            String agentName, String chunk, ChatInlineImageRenderer inlineImages) {
        ensureOpen();
        setStatus(PanelStatus.EXECUTING, "智能体返回结果中...");
        renderer.appendSubAgentReply(agentName, chunk, inlineImages);
    }

    public void markSubAgentResult(String agentName, String briefResult) {
        ensureOpen();
        setStatus(PanelStatus.EXECUTING, "智能体已完成");
        renderer.markSubAgentResult(agentName, briefResult);
    }

    public void completeSubAgentIfPresent(String agentName) {
        ensureOpen();
        renderer.completeSubAgentIfPresent(agentName);
    }

    void completeSubAgentIfPresent(String agentName,
            com.javaclaw.framework.api.ToolExecutionStatus executionStatus) {
        ensureOpen();
        renderer.completeSubAgentIfPresent(agentName, executionStatus);
    }

    public void setReplying() {
        setStatus(PanelStatus.REPLYING, "回复中...");
    }

    public void updateMetrics(long tokensIn, long tokensOut, String costText) {
        if (closed.get()) return;
        viewModel.setMetrics(tokensIn, tokensOut, costText);
    }

    void appendToolCall(String name, String input, ToolState status) {
        appendToolCall(name, "", input, status);
    }

    void appendToolCall(String name, String invocationId, String input, ToolState status) {
        ensureOpen();
        String summary = switch (status == null ? ToolState.RUNNING : status) {
            case SUCCEEDED -> "工具已完成";
            case FAILED -> "工具执行失败";
            case WAITING -> "等待工具授权...";
            case UNCERTAIN -> "工具结果待核验";
            case REOBSERVE -> "等待重新观察";
            case UNKNOWN -> "工具状态未知";
            case STOPPED -> "工具已停止";
            case RUNNING -> "执行工具中...";
        };
        setStatus(PanelStatus.EXECUTING, summary);
        renderer.appendToolCall(name, invocationId, input, status);
    }

    public void appendToolResult(String name, String result) {
        appendToolResult(name, result, null);
    }

    void appendToolResult(String name, String result, ChatInlineImageRenderer inlineImages) {
        appendToolResult(name, "", result, null,
                com.javaclaw.framework.api.ToolExecutionStatus.UNKNOWN, inlineImages);
    }

    void appendToolResult(String name, String invocationId, String result,
            JsonNode output, com.javaclaw.framework.api.ToolExecutionStatus executionStatus,
            ChatInlineImageRenderer inlineImages) {
        ensureOpen();
        String summary = switch (ThinkingContentRenderer.resultKind(executionStatus)) {
            case FAILED -> "工具执行失败";
            case UNCERTAIN -> "工具结果待核验";
            case REOBSERVE -> "等待重新观察";
            case PENDING -> "等待工具授权...";
            case SUCCEEDED -> "工具已完成";
            case UNKNOWN -> "工具状态未知";
        };
        setStatus(PanelStatus.EXECUTING, summary);
        renderer.appendToolResult(name, invocationId, result, output, executionStatus, inlineImages);
    }

    void updateToolReceipt(String name, EffectReceiptV1.Status receiptStatus) {
        updateToolReceipt(name, "", receiptStatus, "");
    }

    void updateToolReceipt(String name, String invocationId,
            EffectReceiptV1.Status receiptStatus, String reason) {
        updateToolReceipt(name, invocationId, receiptStatus, reason, null);
    }

    void updateToolReceipt(String name, String invocationId,
            EffectReceiptV1.Status receiptStatus, String reason, JsonNode metadata) {
        ensureOpen();
        renderer.updateToolReceipt(name, invocationId, receiptStatus, reason, metadata);
    }

    public void appendToolFailure(String name, String detail) {
        appendToolFailure(name, "", detail);
    }

    void appendToolFailure(String name, String invocationId, String detail) {
        ensureOpen();
        setStatus(PanelStatus.EXECUTING, "工具执行失败");
        renderer.appendToolFailure(name, invocationId, detail);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stopElapsedTicker();
        viewModel.statusTypeProperty().removeListener(statusTypeListener);
        if (renderer != null) renderer.clear();
    }

    boolean isClosed() {
        return closed.get();
    }

    ThinkingPanelViewModel viewModel() {
        return viewModel;
    }

    private void startElapsedTicker() {
        stopElapsedTicker();
        elapsedTicker = new Timeline(
                new KeyFrame(Duration.millis(100), event -> refreshElapsedLabel()));
        elapsedTicker.setCycleCount(Animation.INDEFINITE);
        elapsedTicker.play();
    }

    private void stopElapsedTicker() {
        if (elapsedTicker == null) return;
        elapsedTicker.stop();
        elapsedTicker = null;
    }

    private void refreshElapsedLabel() {
        long millis = Math.max(0, System.currentTimeMillis() - streamStartMillis);
        if (millis < 60_000) {
            viewModel.setElapsed(String.format("%.1fs", millis / 1000.0));
            return;
        }
        long seconds = millis / 1000;
        viewModel.setElapsed((seconds / 60) + "m " + (seconds % 60) + "s");
    }

    private void applyStatusStyle(PanelStatus type) {
        String token = (type == null ? PanelStatus.IDLE : type)
                .name().toLowerCase(Locale.ROOT);
        statusTag.getStyleClass().removeAll(
                "tp-status-tag-idle", "tp-status-tag-thinking",
                "tp-status-tag-planning", "tp-status-tag-executing",
                "tp-status-tag-replying");
        statusTag.getStyleClass().add("tp-status-tag-" + token);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Thinking Panel 已关闭");
        }
    }
}
