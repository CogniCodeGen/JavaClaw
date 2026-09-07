package com.javaclaw.protocol;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpCatalogRefresh;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.WorkspaceId;

/** MCP Endpoint、健康与 Catalog 的 Protocol v3 DTO。 */
public final class McpRpcContracts {
    private McpRpcContracts() {}

    /** @param workspaceId Workspace */
    public record WorkspaceQuery(WorkspaceId workspaceId) {
        /** 校验 Workspace。 */
        public WorkspaceQuery {
            Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    /** @param endpointId MCP Endpoint 标识 */
    public record EndpointQuery(String endpointId) {
        /** 校验标识。 */
        public EndpointQuery {
            endpointId = identifier(endpointId);
        }
    }

    /**
     * @param endpointId 标识
     * @param spec 完整 HTTPS 配置
     */
    public record EndpointWritePayload(String endpointId, McpEndpointSpec spec) {
        /** 校验写入值。 */
        public EndpointWritePayload {
            endpointId = identifier(endpointId);
            Objects.requireNonNull(spec, "spec");
        }
    }

    /**
     * 已验签 Bundle stdio MCP 的平台注册输入。
     *
     * @param endpointId Endpoint 标识
     * @param workspaceId 所属 Workspace
     * @param bundleId 已安装且启用的签名 Bundle
     * @param displayName 用户可见名称
     * @param requestTimeout 单次 stdio 交换超时
     */
    public record SignedBundleRegisterPayload(
            String endpointId, WorkspaceId workspaceId, String bundleId, String displayName, Duration requestTimeout) {
        /** 校验非敏感注册配置。 */
        public SignedBundleRegisterPayload {
            endpointId = identifier(endpointId);
            Objects.requireNonNull(workspaceId, "workspaceId");
            bundleId = identifier(bundleId);
            displayName = text(displayName, "displayName");
            requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (requestTimeout.isZero()
                    || requestTimeout.isNegative()
                    || requestTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
                throw new IllegalArgumentException("requestTimeout must be between 1 nanosecond and 2 minutes");
            }
        }
    }

    /** @param endpoints 端点最新版本或历史 */
    public record EndpointListResult(List<McpEndpoint> endpoints) {
        /** 复制结果。 */
        public EndpointListResult {
            endpoints = List.copyOf(endpoints);
        }
    }

    /**
     * Catalog 分页查询。
     *
     * @param endpointId Endpoint 标识
     * @param kind 可选条目类型
     * @param cursor 服务端 opaque cursor；首页为空
     * @param limit 本页上限，1 至 200
     */
    public record CatalogQuery(String endpointId, Optional<McpCatalogKind> kind, Optional<String> cursor, int limit) {
        /** 校验分页。 */
        public CatalogQuery {
            endpointId = identifier(endpointId);
            kind = Objects.requireNonNull(kind, "kind");
            cursor = Objects.requireNonNull(cursor, "cursor").map(value -> checkedCursor(value.strip()));
            if (limit < 1 || limit > 200) {
                throw new IllegalArgumentException("limit must be between 1 and 200");
            }
        }

        /** @return 解码后的零基 offset */
        public int offset() {
            return cursor.map(Integer::parseInt).orElse(0);
        }
    }

    /** @param page 权威 Catalog 页 */
    public record CatalogResult(McpCatalogPage page) {
        /** 校验结果。 */
        public CatalogResult {
            Objects.requireNonNull(page, "page");
        }
    }

    /** @param refresh 最近一次刷新；从未刷新时为空 */
    public record CatalogRefreshResult(Optional<McpCatalogRefresh> refresh) {
        /** 复制 Optional。 */
        public CatalogRefreshResult {
            refresh = Objects.requireNonNull(refresh, "refresh");
        }
    }

    /**
     * HTTPS MCP 外部目录分页条件。
     *
     * @param endpointId Endpoint 标识
     * @param cursor 可选远端 opaque cursor
     */
    public record ExternalPageQuery(String endpointId, Optional<String> cursor) {
        /** 校验查询。 */
        public ExternalPageQuery {
            endpointId = identifier(endpointId);
            cursor = Objects.requireNonNull(cursor, "cursor").map(value -> boundedText(value, "cursor", 4_096));
        }
    }

    /**
     * HTTPS MCP Resource 显式读取条件。
     *
     * @param endpointId Endpoint 标识
     * @param uri 远端声明的绝对 URI
     */
    public record ResourceReadQuery(String endpointId, String uri) {
        /** 校验查询。 */
        public ResourceReadQuery {
            endpointId = identifier(endpointId);
            uri = resourceUri(uri);
        }
    }

    /**
     * HTTPS MCP Prompt 显式获取条件。
     *
     * @param endpointId Endpoint 标识
     * @param name Prompt 名称
     * @param arguments 用户显式提供的字符串参数
     */
    public record PromptGetQuery(String endpointId, String name, Map<String, String> arguments) {
        /** 复制并校验查询。 */
        public PromptGetQuery {
            endpointId = identifier(endpointId);
            name = boundedText(name, "name", 240);
            Map<String, String> checkedArguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
            if (checkedArguments.size() > 32) {
                throw new IllegalArgumentException("arguments must not exceed 32 entries");
            }
            checkedArguments.forEach((key, value) -> {
                boundedText(key, "argument name", 240);
                boundedValue(value, "argument value", 8_192);
            });
            arguments = checkedArguments;
        }
    }

    /** @param page 强类型外部 Resource 页 */
    public record ResourcePageResult(McpResourcePage page) {
        /** 校验结果。 */
        public ResourcePageResult {
            Objects.requireNonNull(page, "page");
        }
    }

    /** @param resource 强类型外部 Resource 内容 */
    public record ResourceReadResult(McpResourceReadResult resource) {
        /** 校验结果。 */
        public ResourceReadResult {
            Objects.requireNonNull(resource, "resource");
        }
    }

    /** @param page 强类型外部 Prompt 页 */
    public record PromptPageResult(McpPromptPage page) {
        /** 校验结果。 */
        public PromptPageResult {
            Objects.requireNonNull(page, "page");
        }
    }

    /** @param prompt 强类型外部 Prompt 结果 */
    public record PromptResult(McpPromptResult prompt) {
        /** 校验结果。 */
        public PromptResult {
            Objects.requireNonNull(prompt, "prompt");
        }
    }

    /** @param endpointId OAuth Endpoint 标识 */
    public record OAuthStartPayload(String endpointId) {
        /** 校验标识。 */
        public OAuthStartPayload {
            endpointId = identifier(endpointId);
        }
    }

    /**
     * OAuth 状态读取条件；授权标识和 Endpoint 标识必须且只能提供一个。
     *
     * @param authorizationId OAuth 流程标识
     * @param endpointId Endpoint 标识，用于 Desktop 重连后恢复最近状态
     */
    public record OAuthQuery(Optional<String> authorizationId, Optional<String> endpointId) {
        /** 校验互斥条件。 */
        public OAuthQuery {
            authorizationId =
                    Objects.requireNonNull(authorizationId, "authorizationId").map(McpRpcContracts::identifier);
            endpointId = Objects.requireNonNull(endpointId, "endpointId").map(McpRpcContracts::identifier);
            if (authorizationId.isPresent() == endpointId.isPresent()) {
                throw new IllegalArgumentException("exactly one OAuth query identifier is required");
            }
        }

        /** @param authorizationId OAuth 流程标识 @return 精确流程查询 */
        public static OAuthQuery authorization(String authorizationId) {
            return new OAuthQuery(Optional.of(authorizationId), Optional.empty());
        }

        /** @param endpointId Endpoint 标识 @return 最近流程查询 */
        public static OAuthQuery endpoint(String endpointId) {
            return new OAuthQuery(Optional.empty(), Optional.of(endpointId));
        }
    }

    /** @param authorization 脱敏状态；Endpoint 尚无授权流程时为空 */
    public record OAuthResult(Optional<com.javaclaw.api.McpOAuthAuthorization> authorization) {
        /** 复制可选状态。 */
        public OAuthResult {
            authorization = Objects.requireNonNull(authorization, "authorization");
        }
    }

    private static String identifier(String value) {
        String id = Objects.requireNonNull(value, "endpointId").strip();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("endpointId contains unsupported characters");
        }
        return id;
    }

    private static String checkedCursor(String value) {
        if (!value.matches("0|[1-9][0-9]{0,8}")) {
            throw new IllegalArgumentException("cursor is invalid");
        }
        return value;
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }

    private static String boundedText(String value, String name, int maximum) {
        String checked = text(value, name);
        if (checked.length() > maximum || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return checked;
    }

    private static String boundedValue(String value, String name, int maximum) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.length() > maximum || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return checked;
    }

    private static String resourceUri(String value) {
        String checked = boundedText(value, "uri", 4_096);
        java.net.URI uri = java.net.URI.create(checked);
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("uri must be absolute");
        }
        return checked;
    }
}
