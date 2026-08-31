package com.javaclaw.desktop;

import java.util.Objects;

/**
 * 管理操作的用户可见反馈，不携带异常对象、原始响应或敏感请求内容。
 *
 * @param severity 展示严重级别
 * @param message 已脱敏的用户可读说明；空字符串表示没有待展示反馈
 */
record ManagementFeedback(Severity severity, String message) {
    ManagementFeedback {
        Objects.requireNonNull(severity, "severity");
        message = Objects.toString(message, "");
    }

    static ManagementFeedback idle() {
        return new ManagementFeedback(Severity.IDLE, "");
    }

    String styleClass() {
        return "management-feedback-" + severity.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 用户可见反馈级别；不会改变服务端操作结果或重试策略。 */
    enum Severity {
        IDLE,
        RUNNING,
        SUCCESS,
        VALIDATION_ERROR,
        SERVER_ERROR
    }
}
