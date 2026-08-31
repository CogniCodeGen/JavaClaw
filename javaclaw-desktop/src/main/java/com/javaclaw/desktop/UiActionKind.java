package com.javaclaw.desktop;

import java.util.List;

import javafx.scene.control.ButtonBase;

/** 管理页操作的视觉语义；样式只映射到原版 {@code jc-btn-*} 设计令牌。 */
enum UiActionKind {
    PRIMARY("jc-btn-primary"),
    SECONDARY("jc-btn-soft"),
    GHOST("jc-btn-ghost"),
    DANGER("jc-btn-danger");

    private static final List<String> ACTION_STYLES =
            List.of("jc-btn-primary", "jc-btn-soft", "jc-btn-ghost", "jc-btn-danger");
    private final String styleClass;

    UiActionKind(String styleClass) {
        this.styleClass = styleClass;
    }

    /** 清除旧操作语义并应用当前语义，保留按钮自身及尺寸样式。 */
    void apply(ButtonBase button) {
        button.getStyleClass().removeAll(ACTION_STYLES);
        if (!button.getStyleClass().contains("jc-btn")) {
            button.getStyleClass().add("jc-btn");
        }
        if (!button.getStyleClass().contains("jc-btn-sm")) {
            button.getStyleClass().add("jc-btn-sm");
        }
        button.getStyleClass().add(styleClass);
    }
}
