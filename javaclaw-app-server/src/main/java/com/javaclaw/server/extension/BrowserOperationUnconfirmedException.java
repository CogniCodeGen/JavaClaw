package com.javaclaw.server.extension;

import com.javaclaw.api.CanonicalPayload;

/**
 * 已派发的浏览器操作因未授权来源失去成功回执；工具平台应提交失败结果，并根据真实续接状态决定是否结束当前 Turn。
 *
 * <p>该异常不携带原始 Worker 异常、网页 URL、请求参数或秘密。动作账本仍保持未确认，任何同键重试都不得重放副作用。
 */
public final class BrowserOperationUnconfirmedException extends RuntimeException {
    private static final CanonicalPayload RESULT =
            new CanonicalPayload("{\"detail\":\"检测到未授权页面来源；原操作结果未确认，不能自动重试。请重新观察页面或人工核验。\","
                    + "\"dispatched\":true,\"retryAllowed\":false,\"status\":\"BROWSER_ACTION_UNCONFIRMED\"}");

    /** 创建固定脱敏失败，不承诺用户已批准来源或后继 Turn 已创建。 */
    public BrowserOperationUnconfirmedException() {
        super("浏览器操作结果未确认，需要重新观察页面或人工核验");
    }

    /** @return 可持久化为失败 ToolResult 的固定规范 JSON；不包含网页或秘密内容 */
    public CanonicalPayload payload() {
        return RESULT;
    }
}
