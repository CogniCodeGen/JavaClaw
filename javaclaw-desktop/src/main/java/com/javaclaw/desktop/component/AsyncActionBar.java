package com.javaclaw.desktop.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;

/** 统一表达表单脏状态、异步进度、成功和失败的动作栏。 */
public final class AsyncActionBar extends HBox {
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label status = new Label();
    private final Region spacer = new Region();
    private final Text lineMeasure = new Text("Ag");

    /**
     * 创建动作栏；动作节点按传入顺序显示在右侧。
     *
     * @param actions 按钮或其他动作节点
     */
    public AsyncActionBar(Node... actions) {
        progress.setMaxSize(16, 16);
        progress.setVisible(false);
        progress.setManaged(false);
        status.getStyleClass().add("platform-action-status");
        status.setWrapText(true);
        lineMeasure.fontProperty().bind(status.fontProperty());
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(progress, status, spacer);
        for (Node action : Objects.requireNonNull(actions, "actions")) {
            getChildren().add(Objects.requireNonNull(action, "action"));
        }
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("platform-action-bar");
    }

    /** @return 高度随可用宽度变化，供设置窗口在按钮换行时预留足够空间 */
    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    /** 保留可容纳最长动作的最小宽度，不再要求所有按钮必须排在一行。 */
    @Override
    protected double computeMinWidth(double height) {
        double widest =
                actions().stream().mapToDouble(this::preferredWidth).max().orElse(0);
        return snappedLeftInset() + widest + snappedRightInset();
    }

    /** 单行沿用 HBox 的测量；空间不足时为提示及完整按钮预留多行高度。 */
    @Override
    protected double computePrefHeight(double width) {
        if (!wraps(width)) {
            return super.computePrefHeight(width);
        }
        double available = availableWidth(width);
        List<ActionRow> rows = rows(available);
        double information = informationHeight(available);
        double gaps = Math.max(0, rows.size() - 1) + (information > 0 && !rows.isEmpty() ? 1 : 0);
        return snappedTopInset()
                + information
                + rows.stream().mapToDouble(ActionRow::height).sum()
                + gaps * getSpacing()
                + snappedBottomInset();
    }

    /** 多行模式不能压缩回单行，否则底部按钮会再次不可达。 */
    @Override
    protected double computeMinHeight(double width) {
        return wraps(width) ? computePrefHeight(width) : super.computeMinHeight(width);
    }

    /** 宽屏直接复用原 HBox 像素布局；窄屏仅改变排列，不缩小按钮或丢弃状态说明。 */
    @Override
    protected void layoutChildren() {
        if (!wraps(getWidth())) {
            super.layoutChildren();
            return;
        }
        double available = availableWidth(getWidth());
        double information = informationHeight(available);
        layoutInformation(available, information);
        double y = snappedTopInset() + information + (information > 0 ? getSpacing() : 0);
        for (ActionRow row : rows(available)) {
            double x = snappedLeftInset() + available - row.width();
            for (Node action : row.actions()) {
                double width = preferredWidth(action);
                double height = preferredHeight(action, width);
                action.resizeRelocate(snapPositionX(x), snapPositionY(y + (row.height() - height) / 2), width, height);
                x += width + getSpacing();
            }
            y += row.height() + getSpacing();
        }
    }

    private boolean wraps(double width) {
        return width >= 0
                && (width < super.computePrefWidth(-1)
                        || preferredHeight(status, availableWidth(width)) > maximumStatusHeight());
    }

    private double availableWidth(double width) {
        return Math.max(0, width - snappedLeftInset() - snappedRightInset());
    }

    private List<Node> actions() {
        return getManagedChildren().stream()
                .filter(node -> node != progress && node != status && node != spacer)
                .toList();
    }

    private List<ActionRow> rows(double available) {
        List<ActionRow> rows = new ArrayList<>();
        List<Node> current = new ArrayList<>();
        double width = 0;
        double height = 0;
        for (Node action : actions()) {
            double actionWidth = preferredWidth(action);
            if (!current.isEmpty() && width + getSpacing() + actionWidth > available) {
                rows.add(new ActionRow(List.copyOf(current), width, height));
                current.clear();
                width = 0;
                height = 0;
            }
            width += (current.isEmpty() ? 0 : getSpacing()) + actionWidth;
            height = Math.max(height, preferredHeight(action, actionWidth));
            current.add(action);
        }
        if (!current.isEmpty()) {
            rows.add(new ActionRow(List.copyOf(current), width, height));
        }
        return rows;
    }

    private double informationHeight(double available) {
        if (status.getText().isEmpty() && !progress.isManaged()) {
            return 0;
        }
        double progressWidth = progress.isManaged() ? preferredWidth(progress) + getSpacing() : 0;
        double textHeight =
                Math.min(preferredHeight(status, Math.max(0, available - progressWidth)), maximumStatusHeight());
        return Math.max(textHeight, progress.isManaged() ? preferredHeight(progress, preferredWidth(progress)) : 0);
    }

    /** 长异常只占三行；Label 的原始文本与 Tooltip 保留完整内容，避免错误信息挤走正文和动作。 */
    private double maximumStatusHeight() {
        return snapSizeY(lineMeasure.getLayoutBounds().getHeight() * 3
                + status.getLineSpacing() * 2
                + status.getInsets().getTop()
                + status.getInsets().getBottom()
                + status.getLabelPadding().getTop()
                + status.getLabelPadding().getBottom());
    }

    private void layoutInformation(double available, double height) {
        double progressWidth = progress.isManaged() ? preferredWidth(progress) : 0;
        if (progress.isManaged()) {
            double progressHeight = preferredHeight(progress, progressWidth);
            progress.resizeRelocate(
                    snappedLeftInset(),
                    snappedTopInset() + (height - progressHeight) / 2,
                    progressWidth,
                    progressHeight);
        }
        double offset = progressWidth > 0 ? progressWidth + getSpacing() : 0;
        status.resizeRelocate(snappedLeftInset() + offset, snappedTopInset(), Math.max(0, available - offset), height);
        spacer.resizeRelocate(snappedLeftInset(), snappedTopInset(), 0, 0);
    }

    private double preferredWidth(Node node) {
        return snapSizeX(Math.max(node.minWidth(-1), Math.min(node.prefWidth(-1), node.maxWidth(-1))));
    }

    private double preferredHeight(Node node, double width) {
        return snapSizeY(Math.max(node.minHeight(width), Math.min(node.prefHeight(width), node.maxHeight(width))));
    }

    /** 当前行的动作顺序及首选宽高；尺寸单位为 JavaFX 逻辑像素，动作列表不可空。 */
    private record ActionRow(List<Node> actions, double width, double height) {}

    /**
     * 更新动作状态和简短说明。
     *
     * @param state 状态语义
     * @param message 说明，可为空字符串
     */
    public void show(ActionState state, String message) {
        ActionState checked = Objects.requireNonNull(state, "state");
        status.setText(Objects.requireNonNullElse(message, ""));
        if (status.getText().isEmpty()) {
            status.setTooltip(null);
        } else {
            Tooltip details = new Tooltip(status.getText());
            details.setWrapText(true);
            details.setMaxWidth(480);
            status.setTooltip(details);
        }
        status.getStyleClass().removeAll("platform-action-dirty", "platform-action-success", "platform-action-error");
        if (!checked.cssClass().isEmpty()) {
            status.getStyleClass().add(checked.cssClass());
        }
        progress.setVisible(checked == ActionState.PENDING);
        progress.setManaged(checked == ActionState.PENDING);
    }

    /** 表单和命令动作状态。 */
    public enum ActionState {
        /** 无提示。 */
        IDLE(""),
        /** 草稿尚未保存。 */
        DIRTY("platform-action-dirty"),
        /** 后台动作执行中。 */
        PENDING(""),
        /** 动作成功。 */
        SUCCESS("platform-action-success"),
        /** 动作失败。 */
        ERROR("platform-action-error");

        private final String cssClass;

        ActionState(String cssClass) {
            this.cssClass = cssClass;
        }

        private String cssClass() {
            return cssClass;
        }
    }
}
