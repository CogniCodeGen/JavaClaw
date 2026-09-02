package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.WorkspaceId;

/**
 * 用户 HTTPS MCP Endpoint 的不可变表单草稿。
 *
 * @param id 稳定标识
 * @param workspaceId 所属 Workspace；新建且尚未选择时为空
 * @param displayName 展示名称
 * @param endpointUri HTTPS 地址文本
 * @param authType 认证方式
 * @param credential 已配置的 Vault 引用
 * @param apiKeyHeader API Key header；其他认证方式为空文本
 * @param privateNetworkGrant 精确私网授权版本
 * @param timeoutSeconds 请求超时秒数
 */
public record McpEndpointDraft(
        String id,
        Optional<WorkspaceId> workspaceId,
        String displayName,
        String endpointUri,
        McpAuthType authType,
        Optional<CredentialRef> credential,
        String apiKeyHeader,
        Optional<PrivateNetworkGrantRef> privateNetworkGrant,
        int timeoutSeconds) {
    /** 规范化用户文本，不提前吞掉领域校验错误。 */
    public McpEndpointDraft {
        id = Objects.requireNonNullElse(id, "");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        displayName = Objects.requireNonNullElse(displayName, "");
        endpointUri = Objects.requireNonNullElse(endpointUri, "");
        Objects.requireNonNull(authType, "authType");
        credential = Objects.requireNonNull(credential, "credential");
        apiKeyHeader = Objects.requireNonNullElse(apiKeyHeader, "");
        privateNetworkGrant = Objects.requireNonNull(privateNetworkGrant, "privateNetworkGrant");
    }

    /**
     * 创建新端点草稿。
     *
     * @param workspaceId 当前 Workspace
     * @return 空草稿
     */
    public static McpEndpointDraft empty(Optional<WorkspaceId> workspaceId) {
        return new McpEndpointDraft(
                "", workspaceId, "", "", McpAuthType.NONE, Optional.empty(), "", Optional.empty(), 30);
    }

    /**
     * 从权威 Endpoint 创建等价草稿。
     *
     * @param endpoint Endpoint 快照
     * @return 可编辑草稿
     */
    public static McpEndpointDraft from(McpEndpoint endpoint) {
        McpEndpoint checked = Objects.requireNonNull(endpoint, "endpoint");
        McpEndpointSpec spec = checked.spec();
        return new McpEndpointDraft(
                checked.id(),
                Optional.of(spec.workspaceId()),
                spec.displayName(),
                spec.endpointUri().map(URI::toString).orElse(""),
                spec.authType(),
                spec.credential(),
                spec.apiKeyHeader().orElse(""),
                spec.privateNetworkGrant(),
                Math.toIntExact(spec.requestTimeout().toSeconds()));
    }

    /**
     * 转换为只允许用户创建的 HTTPS Endpoint 配置。
     *
     * @return 已通过核心契约校验的配置
     */
    public McpEndpointSpec toSpec() {
        Optional<String> header =
                authType == McpAuthType.API_KEY ? Optional.of(apiKeyHeader.strip()) : Optional.empty();
        return new McpEndpointSpec(
                workspaceId.orElseThrow(() -> new IllegalArgumentException("必须选择 Workspace")),
                displayName,
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create(endpointUri.strip())),
                Optional.empty(),
                authType,
                credential,
                header,
                privateNetworkGrant,
                Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * 替换凭据引用。
     *
     * @param reference 新凭据
     * @return 新草稿
     */
    public McpEndpointDraft withCredential(Optional<CredentialRef> reference) {
        return new McpEndpointDraft(
                id,
                workspaceId,
                displayName,
                endpointUri,
                authType,
                reference,
                apiKeyHeader,
                privateNetworkGrant,
                timeoutSeconds);
    }
}
