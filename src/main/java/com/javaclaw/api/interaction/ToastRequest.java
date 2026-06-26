package com.javaclaw.api.interaction;

/**
 * 向用户发送一次非阻塞通知的请求（对应 {@link ConfirmKind#NOTIFY} 级别的工具调用）。
 *
 * @param title   标题（通常为工具名）
 * @param message 正文
 */
public record ToastRequest(String title, String message) {

    public ToastRequest {
        if (title == null) title = "";
        if (message == null) message = "";
    }
}
