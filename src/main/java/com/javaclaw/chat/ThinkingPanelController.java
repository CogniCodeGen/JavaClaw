package com.javaclaw.chat;

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
    @FXML private Label statusDot;
    @FXML private Label statusLabel;
    @FXML private Label statusTag;
    @FXML private Label elapsedLabel;
    @FXML private Label tokensInValue;
    @FXML private Label tokensOutValue;
    @FXML private Label tokenDetailsValue;
    @FXML private Label emptyHint;
    @FXML private VBox dynamicSectionsHost;

    private final ThinkingPanelViewModel viewModel;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ChangeListener<String> statusTypeListener =
            (observable, previous, current) -> applyStatusStyle(current);
    private ThinkingContentRenderer renderer;
    private Timeline elapsedTicker;
    private long streamStartMillis;

    public ThinkingPanelController() {
        this(new ThinkingPanelViewModel());
    }

    ThinkingPanelController(ThinkingPanelViewModel viewModel) {
        this.viewModel = java.util.Objects.requireNonNull(viewModel, "viewModel");
    }

    @FXML
    private void initialize() {
        renderer = new ThinkingContentRenderer(dynamicSectionsHost);
        statusLabel.textProperty().bind(viewModel.statusTextProperty());
        statusTag.textProperty().bind(viewModel.statusTextProperty());
        elapsedLabel.textProperty().bind(viewModel.elapsedProperty());
        tokensInValue.textProperty().bind(viewModel.tokensInProperty().asString());
        tokensOutValue.textProperty().bind(viewModel.tokensOutProperty().asString());
        tokenDetailsValue.textProperty().bind(viewModel.tokenDetailsProperty());
        emptyHint.visibleProperty().bind(viewModel.emptyProperty());
        emptyHint.managedProperty().bind(viewModel.emptyProperty());
        viewModel.statusTypeProperty().addListener(statusTypeListener);
        contentBox.heightProperty().addListener(
                (observable, previous, current) -> scrollPane.setVvalue(1.0));
        applyStatusStyle(viewModel.statusTypeProperty().get());
    }

    public VBox getRoot() {
        return root;
    }

    /** 清空上一轮动态内容并开始计时。 */
    public void startNewStream() {
        ensureOpen();
        renderer.clear();
        viewModel.setEmpty(false);
        setStatus("thinking", "思考中...");
        updateMetrics(TurnMetrics.ZERO);
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
        viewModel.setEmpty(true);
        viewModel.setElapsed("0.0s");
        viewModel.setMetrics(0, 0, "缓存 0 · 写入 0 · 推理 —");
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
        setStatus("executing", (agentName == null ? "智能体" : agentName) + " 思考中...");
        renderer.appendSubAgentThinking(agentName, thinking);
    }

    public void markSubAgentResult(String agentName, String briefResult) {
        ensureOpen();
        renderer.markSubAgentResult(agentName, briefResult);
    }

    public void setReplying() {
        setStatus("replying", "回复中...");
    }

    public void updateMetrics(long tokensIn, long tokensOut, String tokenDetailsText) {
        if (closed.get()) return;
        viewModel.setMetrics(tokensIn, tokensOut, tokenDetailsText);
    }

    public void updateMetrics(TurnMetrics metrics) {
        if (closed.get()) return;
        TurnMetrics value = metrics == null ? TurnMetrics.ZERO : metrics;
        String reasoning = value.reasoningTokens() == 0
                ? "—" : Long.toString(value.reasoningTokens());
        viewModel.setMetrics(value.inputTokens(), value.outputTokens(),
                "缓存 " + value.cacheReadInputTokens()
                        + " · 写入 " + value.cacheWriteInputTokens()
                        + " · 推理 " + reasoning);
    }

    public void appendToolCall(String name, String input, String status) {
        ensureOpen();
        renderer.appendToolCall(name, input, status);
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
        statusDot.getStyleClass().removeAll(
                "status-thinking", "status-planning", "status-executing",
                "status-replying", "status-idle");
        statusDot.getStyleClass().add("status-" + normalized);
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
