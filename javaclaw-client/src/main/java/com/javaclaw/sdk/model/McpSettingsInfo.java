package com.javaclaw.sdk.model;

import java.util.Set;

/**
 * MCP 设置表单的严格元数据；凭据必须通过单独的 SecretStore 接口写入。
 *
 * @param transport http 或由插件生成的 stdio
 * @param url HTTPS 端点，stdio 为空
 * @param networkAllowlist 精确允许的网络主机
 * @param authentication none、bearer、apiKey 或 oauth
 * @param credentialName 静态凭据引用名，不是凭据值
 * @param headerName API Key 头名称，其余可为空
 * @param clientId 已注册 OAuth 客户端 id，可为空
 * @param scopes 最小授权范围
 * @param timeoutMillis 单请求毫秒上限
 * @param outputLimitBytes 单响应字节上限
 * @param workspaceId 可选工作区绑定，空字符串表示不绑定；私网 OAuth 需要绑定
 */
public record McpSettingsInfo(
        String transport,
        String url,
        Set<String> networkAllowlist,
        String authentication,
        String credentialName,
        String headerName,
        String clientId,
        Set<String> scopes,
        long timeoutMillis,
        long outputLimitBytes,
        String workspaceId) {
    /** 固定 allowlist 与 scopes，不授予任何额外网络权限。 */
    public McpSettingsInfo {
        networkAllowlist = Set.copyOf(networkAllowlist);
        scopes = Set.copyOf(scopes);
        workspaceId = workspaceId == null ? "" : workspaceId;
    }
}
