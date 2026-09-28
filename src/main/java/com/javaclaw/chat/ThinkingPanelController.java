package com.javaclaw.chat;

import com.javaclaw.loop.model.LoopStatus;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thinking Panel 的 FXML Controller。
 *
 * <p>所有入口都受 JavaFX Application Thread 约束。Controller 只把会话事件映射为
 * {@link ThinkingPanelViewModel} 状态和动态渲染命令；关闭后停止计时，重复关闭无副作用。</p>
 */
public final class ThinkingPanelController implements AutoCloseable {

    private static final Set<String> STATUS_TYPES = Set.of(
            "thinking", "planning", "executing", "replying", "idle");

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
    private final ChangeListener<String> statusTypeListener =
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
        setStatus("thinking", "思考中...");
        updateMetrics(0, 0, "¥0.00");
        streamStartMillis = System.currentTimeMillis();
        startElapsedTicker();
    }

    public void endStream() {
        endStream("处理完成", "已完成");
    }

    public void endStreamCancelled() {
        endStream("已取消", "已停止");
    }

    public void endStreamFailed() {
        endStream("失败", "失败");
    }

    private void endStream(String panelStatus, String agentStatus) {
        if (closed.get()) return;
        setStatus("idle", panelStatus);
        stopElapsedTicker();
        refreshElapsedLabel();
        renderer.finish(agentStatus);
    }

    public void reset() {
        if (closed.get()) return;
        stopElapsedTicker();
        renderer.clear();
        followLatest = true;
        viewModel.setEmpty(true);
        viewModel.setElapsed("0.0s");
        viewModel.setMetrics(0, 0, "¥0.00");
        viewModel.setStatus("idle", "等待中");
    }

    public void setStatus(String type, String text) {
        if (closed.get()) return;
        viewModel.setStatus(normalizeStatus(type), text);
    }

    public void recordPipelineProgress(
            String stageId, String label, String status, String detail) {
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
        setStatus("thinking", "思考中...");
        renderer.appendThinking(chunk);
    }

    public void updatePlan(String hint) {
        ensureOpen();
        setStatus("planning", "规划中...");
        renderer.updatePlan(hint);
    }

    public void appendSubAgentThinking(String agentName, String thinking) {
        ensureOpen();
        setStatus("executing", "智能体执行中...");
        renderer.appendSubAgentThinking(agentName, thinking);
    }

    /** A reply stream is still in progress; only a ToolResult marks it complete. */
    public void markSubAgentReplying(String agentName) {
        ensureOpen();
        setStatus("executing", "智能体返回结果中...");
        renderer.markSubAgentReplying(agentName);
    }

    public void appendSubAgentReply(String agentName, String chunk) {
        appendSubAgentReply(agentName, chunk, null);
    }

    void appendSubAgentReply(
            String agentName, String chunk, ChatInlineImageRenderer inlineImages) {
        ensureOpen();
        setStatus("executing", "智能体返回结果中...");
        renderer.appendSubAgentReply(agentName, chunk, inlineImages);
    }

    public void markSubAgentResult(String agentName, String briefResult) {
        ensureOpen();
        setStatus("executing", "智能体已完成");
        renderer.markSubAgentResult(agentName, briefResult);
    }

    public void completeSubAgentIfPresent(String agentName) {
        ensureOpen();
        renderer.completeSubAgentIfPresent(agentName);
    }

    public void setReplying() {
        setStatus("replying", "回复中...");
    }

    public void updateMetrics(long tokensIn, long tokensOut, String costText) {
        if (closed.get()) return;
        viewModel.setMetrics(tokensIn, tokensOut, costText);
    }

    public void appendToolCall(String name, String input, String status) {
        ensureOpen();
        String normalized = status == null ? "running" : status.toLowerCase(java.util.Locale.ROOT);
        String summary = switch (normalized) {
            case "ok", "done", "completed", "success" -> "工具已完成";
            case "error", "failed", "failure" -> "工具执行失败";
            case "waiting" -> "等待工具授权...";
            default -> "执行工具中...";
        };
        setStatus("executing", summary);
        renderer.appendToolCall(name, input, status);
    }

    public void appendToolResult(String name, String result) {
        appendToolResult(name, result, null);
    }

    void appendToolResult(String name, String result, ChatInlineImageRenderer inlineImages) {
        ensureOpen();
        setStatus("executing", "工具已完成");
        renderer.appendToolResult(name, result, inlineImages);
    }

    public void appendToolFailure(String name, String detail) {
        ensureOpen();
        setStatus("executing", "工具执行失败");
        renderer.appendToolFailure(name, detail);
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

    private void applyStatusStyle(String type) {
        String normalized = normalizeStatus(type);
        statusTag.getStyleClass().removeAll(
                "tp-status-tag-idle", "tp-status-tag-thinking",
                "tp-status-tag-planning", "tp-status-tag-executing",
                "tp-status-tag-replying");
        statusTag.getStyleClass().add("tp-status-tag-" + normalized);
    }

    private static String normalizeStatus(String type) {
        return STATUS_TYPES.contains(type) ? type : "idle";
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Thinking Panel 已关闭");
        }
    }
}
