package com.javaclaw.chat;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 将一轮对话中的多个子智能体输出收纳到一个可折叠活动框。 */
final class ChildAgentActivityView implements AutoCloseable {

    private final ExpandableMarkdownBlockFactory expandableBlocks;
    private final VBox root = new VBox(4);
    private final VBox entries = new VBox(3);
    private final Label summary = new Label();
    private final Label count = new Label();
    private final Button toggle = new Button("▼");
    private final Map<String, ExpandableMarkdownBlockView> blocks = new LinkedHashMap<>();
    private final BooleanProperty expanded = new SimpleBooleanProperty(true);
    private boolean closed;
    private Animation transition;
    private String activeKey;

    ChildAgentActivityView(ExpandableMarkdownBlockFactory expandableBlocks) {
        this.expandableBlocks = Objects.requireNonNull(expandableBlocks, "expandableBlocks");
        configureRoot();
    }

    VBox root() {
        return root;
    }

    BooleanProperty expandedProperty() {
        return expanded;
    }

    void append(String name, ChatStreamRenderer.ChunkKind kind, String content) {
        append(name, kind, content, false);
    }

    void append(String name, ChatStreamRenderer.ChunkKind kind, String content,
                boolean forceNewEntry) {
        if (closed) return;
        String displayName = name == null || name.isBlank() ? "专家回复" : name;
        ExpandableMarkdownBlockView block = entry(displayName, forceNewEntry);
        if (kind == ChatStreamRenderer.ChunkKind.THINKING) {
            summary.setText(displayName + " 正在思考");
            return;
        }
        if (content == null || content.isBlank()) return;
        block.revealContent();
        block.bubble().appendText(content);
        summary.setText(displayName + " 正在返回结果");
    }

    VBox activeContentHost() {
        ExpandableMarkdownBlockView block = activeKey == null ? null : blocks.get(activeKey);
        return block == null ? null : block.contentHost();
    }

    void finish(DeliveryState state) {
        if (closed) return;
        blocks.values().forEach(block -> block.bubble().finish());
        summary.setText(summaryFor(state));
        collapseAnimated();
    }

    private void configureRoot() {
        root.getStyleClass().add("child-agent-activity");
        root.getProperties().put("childAgentActivityView", this);
        root.setPadding(new Insets(5, 8, 6, 8));
        HBox header = new HBox(6);
        header.getStyleClass().add("child-agent-activity-header");
        Label marker = new Label("◈");
        marker.getStyleClass().add("child-agent-activity-marker");
        Label title = new Label("子智能体活动");
        title.getStyleClass().add("child-agent-activity-title");
        count.getStyleClass().add("child-agent-activity-count");
        summary.getStyleClass().add("child-agent-activity-summary");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toggle.getStyleClass().add("bubble-icon-btn");
        toggle.setOnAction(this::toggleRequested);
        header.getChildren().addAll(marker, title, count, spacer, summary, toggle);
        entries.getStyleClass().add("child-agent-activity-entries");
        root.getChildren().addAll(header, entries);
        summary.setText("正在等待子智能体回复");
    }

    private ExpandableMarkdownBlockView entry(String name, boolean forceNew) {
        String key = forceNew ? uniqueKey(name) : name;
        ExpandableMarkdownBlockView existing = forceNew ? null : blocks.get(key);
        if (existing != null) {
            activeKey = key;
            return existing;
        }
        ExpandableMarkdownBlockView created = expandableBlocks.create(
                ExpandableMarkdownBlockFactory.Variant.SUB_AGENT, name, false);
        blocks.put(key, created);
        activeKey = key;
        entries.getChildren().add(created.root());
        count.setText(blocks.size() + " 个");
        com.javaclaw.app.UiMotion.fadeIn(created.root());
        return created;
    }

    private String uniqueKey(String name) {
        if (!blocks.containsKey(name)) return name;
        int suffix = 2;
        while (blocks.containsKey(name + "#" + suffix)) suffix++;
        return name + "#" + suffix;
    }

    private void toggleRequested(ActionEvent ignored) {
        if (expanded.get()) collapseAnimated();
        else expandAnimated();
    }

    private void collapseAnimated() {
        if (!expanded.get() || closed) return;
        expanded.set(false);
        stopTransition();
        entries.setManaged(true);
        entries.setVisible(true);
        FadeTransition fade = fadeTo(entries.getOpacity(), 0);
        fade.setOnFinished(event -> {
            entries.setManaged(false);
            entries.setVisible(false);
            entries.setOpacity(1);
            toggle.setText("▶");
        });
        transition = fade;
        fade.play();
    }

    private void expandAnimated() {
        if (expanded.get() || closed) return;
        expanded.set(true);
        stopTransition();
        entries.setManaged(true);
        entries.setVisible(true);
        entries.setOpacity(0);
        toggle.setText("▼");
        FadeTransition fade = fadeTo(0, 1);
        transition = fade;
        fade.play();
    }

    private FadeTransition fadeTo(double from, double to) {
        FadeTransition fade = new FadeTransition(
                com.javaclaw.app.UiMotion.DURATION_BASE, entries);
        fade.setFromValue(from);
        fade.setToValue(to);
        fade.setInterpolator(javafx.animation.Interpolator.EASE_BOTH);
        return fade;
    }

    private String summaryFor(DeliveryState state) {
        return switch (state) {
            case CANCELLED -> "子智能体活动已停止";
            case FAILED -> "子智能体活动未完成";
            case COMPLETE -> "子智能体活动已完成";
        };
    }

    private void stopTransition() {
        if (transition != null) transition.stop();
        transition = null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        stopTransition();
        blocks.values().forEach(ExpandableMarkdownBlockView::close);
        blocks.clear();
    }
}
