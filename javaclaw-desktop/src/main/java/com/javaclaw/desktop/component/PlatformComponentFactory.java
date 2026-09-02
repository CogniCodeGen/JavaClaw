package com.javaclaw.desktop.component;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.VBox;

/**
 * 构造 JavaClaw Desktop 拥有的通用 JavaFX 组件。
 *
 * <p>该工厂只表达页面、卡片、动作、反馈和列表行等视觉语义，不读取领域状态。组件统一复用 {@code 509f197} 设计基线中的 CSS class，使主壳和声明式扩展页面不会各自发明一套样式。
 */
public final class PlatformComponentFactory {
    /**
     * 创建带统一标题层级的页面。
     *
     * @param title 页面标题
     * @return 可继续追加内容的页面容器
     */
    public VBox page(String title) {
        Label heading = new Label(requireText(title, "title"));
        heading.getStyleClass().addAll("sec-title", "platform-page-title");
        VBox page = new VBox(heading);
        page.getStyleClass().add("platform-page");
        return page;
    }

    /**
     * 创建带标题的通用内容卡片。
     *
     * @param title 分区标题
     * @param content 卡片内容，不能为空节点
     * @return 复用设计系统 token 的卡片
     */
    public VBox section(String title, Node... content) {
        Label heading = new Label(requireText(title, "title"));
        heading.getStyleClass().addAll("grp-title", "platform-section-title");
        VBox section = new VBox(heading);
        section.getStyleClass().addAll("jc-card", "platform-section-card");
        Arrays.stream(Objects.requireNonNull(content, "content"))
                .map(node -> Objects.requireNonNull(node, "content node"))
                .forEach(section.getChildren()::add);
        return section;
    }

    /**
     * 创建采用统一强调级别和尺寸的动作按钮。
     *
     * @param text 按钮文字
     * @param style 动作强调级别
     * @param size 按钮尺寸
     * @return 已应用设计系统 class 的按钮
     */
    public Button action(String text, ActionStyle style, ActionSize size) {
        Button button = new Button(requireText(text, "text"));
        button.getStyleClass()
                .addAll("jc-btn", Objects.requireNonNull(style, "style").cssClass());
        if (Objects.requireNonNull(size, "size") == ActionSize.COMPACT) {
            button.getStyleClass().add("jc-btn-sm");
        }
        return button;
    }

    /**
     * 创建加载、空状态或失败反馈。
     *
     * @param kind 反馈类型
     * @param title 简短结论
     * @param detail 可执行或可理解的补充说明
     * @return 无业务行为的反馈组件
     */
    public VBox feedback(FeedbackKind kind, String title, String detail) {
        FeedbackKind checkedKind = Objects.requireNonNull(kind, "kind");
        Label icon = new Label(checkedKind.icon());
        icon.getStyleClass().add("empty-state-icon");
        Label heading = new Label(requireText(title, "title"));
        heading.getStyleClass().add("empty-state-text");
        Label description = new Label(requireText(detail, "detail"));
        description.setWrapText(true);
        description.getStyleClass().addAll("sec-hint", "empty-state-hint");
        VBox feedback = new VBox(10, icon, heading, description);
        feedback.setAlignment(Pos.CENTER);
        feedback.setAccessibleText(heading.getText() + "。" + description.getText());
        feedback.getStyleClass().addAll("platform-feedback", checkedKind.cssClass());
        return feedback;
    }

    /**
     * 创建只显示单行文本的虚拟化列表单元格。
     *
     * @param text 文本投影
     * @param <T> 列表元素类型
     * @return 可供 ListView 或 ComboBox 复用的 cell
     */
    public <T> ListCell<T> textCell(Function<? super T, String> text) {
        return new TextCell<>(Objects.requireNonNull(text, "text"));
    }

    /**
     * 创建标题和详情分层显示的虚拟化列表单元格。
     *
     * @param title 标题投影
     * @param detail 详情投影
     * @param <T> 列表元素类型
     * @return 复用统一列表层级的 cell
     */
    public <T> ListCell<T> detailCell(Function<? super T, String> title, Function<? super T, String> detail) {
        return new DetailCell<>(Objects.requireNonNull(title, "title"), Objects.requireNonNull(detail, "detail"));
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return text;
    }

    /** 动作按钮的视觉强调级别。 */
    public enum ActionStyle {
        /** 页面主要动作。 */
        PRIMARY("jc-btn-primary"),
        /** 不抢占主动作的柔和动作。 */
        SOFT("jc-btn-soft"),
        /** 关闭、返回等弱动作。 */
        GHOST("jc-btn-ghost"),
        /** 删除、拒绝等破坏性动作。 */
        DANGER("jc-btn-danger");

        private final String cssClass;

        ActionStyle(String cssClass) {
            this.cssClass = cssClass;
        }

        private String cssClass() {
            return cssClass;
        }
    }

    /** 动作按钮的尺寸语义。 */
    public enum ActionSize {
        /** 常规页面动作。 */
        NORMAL,
        /** 卡片或工具栏中的紧凑动作。 */
        COMPACT
    }

    /** 无数据和异步状态的反馈类型。 */
    public enum FeedbackKind {
        /** 后台正在读取页面数据。 */
        LOADING("◌", "platform-feedback-loading"),
        /** 当前没有可显示的数据。 */
        EMPTY("◇", "platform-feedback-empty"),
        /** 页面读取或渲染失败。 */
        ERROR("!", "platform-feedback-error");

        private final String icon;
        private final String cssClass;

        FeedbackKind(String icon, String cssClass) {
            this.icon = icon;
            this.cssClass = cssClass;
        }

        private String icon() {
            return icon;
        }

        private String cssClass() {
            return cssClass;
        }
    }

    private static final class TextCell<T> extends ListCell<T> {
        private final Function<? super T, String> text;

        private TextCell(Function<? super T, String> text) {
            this.text = text;
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            setGraphic(null);
            setText(empty || item == null ? null : Objects.toString(text.apply(item), ""));
        }
    }

    private static final class DetailCell<T> extends ListCell<T> {
        private final Function<? super T, String> titleText;
        private final Function<? super T, String> detailText;
        private final Label title = new Label();
        private final Label detail = new Label();
        private final VBox content = new VBox(3, title, detail);

        private DetailCell(Function<? super T, String> titleText, Function<? super T, String> detailText) {
            this.titleText = titleText;
            this.detailText = detailText;
            title.getStyleClass().add("platform-detail-title");
            detail.setWrapText(true);
            detail.getStyleClass().add("platform-detail-text");
            content.getStyleClass().add("platform-detail-cell");
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            title.setText(Objects.toString(titleText.apply(item), ""));
            detail.setText(Objects.toString(detailText.apply(item), ""));
            setText(null);
            setGraphic(content);
            setAccessibleText(title.getText() + "。" + detail.getText());
        }
    }
}
