package com.javaclaw.api.interaction;

/**
 * 安全秘密输入请求。请求只包含展示文案，不得携带任何密码、令牌或其他秘密值。
 * 实现端应使用不会回显明文的控件，并在返回后尽快清空控件内容。
 */
public record SecretRequest(
        String title,
        String message,
        int timeoutSeconds,
        int maxLength) {

    public SecretRequest {
        title = title == null ? "" : title.strip();
        message = message == null ? "" : message.strip();
        timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 120;
        maxLength = maxLength > 0 ? maxLength : 4096;
    }
}
