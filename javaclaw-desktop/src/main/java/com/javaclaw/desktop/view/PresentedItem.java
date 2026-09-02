package com.javaclaw.desktop.view;

import java.util.Objects;

/**
 * ItemEnvelope 的纯文本可访问投影。
 *
 * @param title 稳定类型标题
 * @param body 已脱敏正文
 * @param styleClass 平台 CSS 类
 */
public record PresentedItem(String title, String body, String styleClass) {
    /** 校验显示字段。 */
    public PresentedItem {
        title = Objects.requireNonNull(title, "title");
        body = Objects.requireNonNull(body, "body");
        styleClass = Objects.requireNonNull(styleClass, "styleClass");
    }
}
