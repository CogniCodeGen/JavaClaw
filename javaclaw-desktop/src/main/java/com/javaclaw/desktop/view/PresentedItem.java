package com.javaclaw.desktop.view;

import java.util.Objects;

/**
 * ItemEnvelope 的纯文本可访问投影。
 *
 * @param title 稳定类型标题
 * @param body 始终可见的正文或失败原因，不可空
 * @param styleClass 平台 CSS 类
 * @param details 默认折叠的技术细节，不可空，无详情时为空串
 * @param collapsible 是否提供展开操作；等待交互和失败原因不能仅放在详情中
 */
public record PresentedItem(String title, String body, String styleClass, String details, boolean collapsible) {
    /**
     * 创建没有折叠详情的正文投影。
     *
     * @param title 非空标题
     * @param body 非空正文
     * @param styleClass 非空平台 CSS 类
     */
    public PresentedItem(String title, String body, String styleClass) {
        this(title, body, styleClass, "", false);
    }

    /** 校验显示字段。 */
    public PresentedItem {
        title = Objects.requireNonNull(title, "title");
        body = Objects.requireNonNull(body, "body");
        styleClass = Objects.requireNonNull(styleClass, "styleClass");
        details = Objects.requireNonNull(details, "details");
    }
}
