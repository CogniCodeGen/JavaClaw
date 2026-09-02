package com.javaclaw.browser.client;

/** 隔离 OAuth 浏览器会话的脱敏状态。 */
public enum McpOAuthBrowserState {
    /** Worker 正在启动并完成首次导航。 */
    STARTING,
    /** 授权窗口已打开，等待固定 loopback callback。 */
    PENDING,
    /** callback 已通过私有管道交给 App Server。 */
    COMPLETED,
    /** 用户或 App Server 已取消。 */
    CANCELLED,
    /** 十分钟硬时限已到。 */
    EXPIRED,
    /** Worker、Broker 或 callback 处理失败。 */
    FAILED
}
