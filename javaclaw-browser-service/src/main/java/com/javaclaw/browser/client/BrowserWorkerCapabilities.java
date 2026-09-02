package com.javaclaw.browser.client;

/**
 * Browser Worker 已由当前平台原生 Runner 验证的交互能力。
 *
 * @param interactiveLogin 人工登录窗口、私有 storage state 与 Broker 链是否已验证
 * @param mcpOAuth MCP OAuth 窗口、loopback 本地截获与 Broker 链是否已验证
 */
public record BrowserWorkerCapabilities(boolean interactiveLogin, boolean mcpOAuth) {
    /**
     * 返回开发 classpath 和无能力回执场景使用的失败关闭配置。
     *
     * @return 两项交互能力都不可用的配置
     */
    public static BrowserWorkerCapabilities unavailable() {
        return new BrowserWorkerCapabilities(false, false);
    }
}
