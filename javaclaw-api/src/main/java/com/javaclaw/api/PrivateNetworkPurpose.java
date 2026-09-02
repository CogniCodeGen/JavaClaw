package com.javaclaw.api;

/** 私网授权允许的受控用途；不能以自由文本扩展权限。 */
public enum PrivateNetworkPurpose {
    /** HTTPS MCP endpoint。 */
    MCP,
    /** Site 及其 Browser Worker 会话。 */
    SITE
}
