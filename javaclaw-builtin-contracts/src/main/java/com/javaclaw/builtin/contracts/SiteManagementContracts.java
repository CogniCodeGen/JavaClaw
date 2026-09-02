package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.builtin.contracts.SiteContracts.CredentialKind;

/** Site 管理中心的强类型写入契约。 */
public final class SiteManagementContracts {
    /** 管理页允许的最大 Origin 数。 */
    public static final int MAXIMUM_ALLOWED_ORIGINS = 100;

    private static final int MAXIMUM_ID_LENGTH = 100;
    private static final int MAXIMUM_NAME_LENGTH = 200;
    private static final int MAXIMUM_ITEM_KEY_LENGTH = 100;

    private SiteManagementContracts() {}

    /**
     * 一条带稳定行键的精确 HTTPS Origin。
     *
     * @param itemKey 编辑会话内稳定且唯一的行键
     * @param origin 不含路径、查询或片段的 HTTPS Origin
     */
    public record AllowedOrigin(String itemKey, URI origin) {
        /** 校验行键并保留 URI 的强类型形状。 */
        public AllowedOrigin {
            itemKey = boundedText(itemKey, "itemKey", MAXIMUM_ITEM_KEY_LENGTH);
            Objects.requireNonNull(origin, "origin");
        }
    }

    /**
     * 创建或编辑 Site 的非 Secret 权限表面。
     *
     * <p>Workspace 来自 ExtensionRequest，不进入表单。CredentialRef、PrivateNetworkGrantRef、revision、authorityRevision
     * 与更新时间均不在此契约中，避免客户端伪造权限来源。
     *
     * @param id Site 标识；编辑时由权威详情数据源绑定
     * @param name 用户可见名称
     * @param origin 凭据只可发送到的主 HTTPS Origin
     * @param allowedOrigins 导航、重定向和子资源允许的精确 Origin
     * @param enabled 是否允许新调用
     */
    public record SaveRequest(String id, String name, URI origin, List<AllowedOrigin> allowedOrigins, boolean enabled) {
        /** 校验有界列表、稳定键唯一性和 Origin 集合。 */
        public SaveRequest {
            id = boundedText(id, "id", MAXIMUM_ID_LENGTH);
            name = boundedText(name, "name", MAXIMUM_NAME_LENGTH);
            Objects.requireNonNull(origin, "origin");
            allowedOrigins = List.copyOf(Objects.requireNonNull(allowedOrigins, "allowedOrigins"));
            if (allowedOrigins.isEmpty() || allowedOrigins.size() > MAXIMUM_ALLOWED_ORIGINS) {
                throw new IllegalArgumentException("allowedOrigins row count is outside the allowed range");
            }
            Set<String> keys = new HashSet<>();
            Set<URI> values = new HashSet<>();
            for (AllowedOrigin allowed : allowedOrigins) {
                if (!keys.add(allowed.itemKey())) {
                    throw new IllegalArgumentException("allowedOrigins contains a duplicate itemKey");
                }
                if (!values.add(allowed.origin())) {
                    throw new IllegalArgumentException("allowedOrigins contains a duplicate Origin");
                }
            }
        }

        /**
         * 返回用于 Site 权限校验的 Origin 集合。
         *
         * @return 不可变 Origin 集合
         */
        public Set<URI> allowedOriginSet() {
            return Set.copyOf(allowedOrigins.stream().map(AllowedOrigin::origin).toList());
        }
    }

    /**
     * 把已有 Site namespace Secret 绑定为 HTTP 凭据。
     *
     * @param siteId Site 标识；由权威详情数据源绑定
     * @param expectedAuthorityRevision 用户看到的权限版本
     * @param kind 仅允许 BEARER 或 API_KEY_HEADER
     * @param credentialId Vault opaque ID；Secret 不进入本契约
     * @param apiKeyHeader API Key header；仅 API_KEY_HEADER 时存在
     */
    public record CredentialBindRequest(
            String siteId,
            long expectedAuthorityRevision,
            CredentialKind kind,
            String credentialId,
            java.util.Optional<String> apiKeyHeader) {
        /** 校验绑定形状；引用存在性由服务端 Vault 端口实时确认。 */
        public CredentialBindRequest {
            siteId = boundedText(siteId, "siteId", MAXIMUM_ID_LENGTH);
            if (expectedAuthorityRevision < 1) {
                throw new IllegalArgumentException("expectedAuthorityRevision must be positive");
            }
            Objects.requireNonNull(kind, "kind");
            if (kind != CredentialKind.BEARER && kind != CredentialKind.API_KEY_HEADER) {
                throw new IllegalArgumentException("Site HTTP credential kind is not bindable");
            }
            credentialId = boundedText(credentialId, "credentialId", MAXIMUM_ID_LENGTH);
            apiKeyHeader = Objects.requireNonNull(apiKeyHeader, "apiKeyHeader")
                    .filter(value -> !value.isBlank())
                    .map(value -> boundedText(value, "apiKeyHeader", 80));
            if (apiKeyHeader.isPresent() != (kind == CredentialKind.API_KEY_HEADER)) {
                throw new IllegalArgumentException("apiKeyHeader does not match credential kind");
            }
        }
    }

    /**
     * 绑定一个当前有效的 Site 私网授权。
     *
     * @param siteId Site 标识；由权威详情数据源绑定
     * @param expectedAuthorityRevision 用户看到的权限版本
     * @param grantId 私网授权稳定 ID；服务端解析并保存其精确当前 revision
     */
    public record PrivateNetworkBindRequest(String siteId, long expectedAuthorityRevision, String grantId) {
        /** 校验非敏感资源身份。 */
        public PrivateNetworkBindRequest {
            siteId = boundedText(siteId, "siteId", MAXIMUM_ID_LENGTH);
            if (expectedAuthorityRevision < 1) {
                throw new IllegalArgumentException("expectedAuthorityRevision must be positive");
            }
            grantId = boundedText(grantId, "grantId", MAXIMUM_ID_LENGTH);
        }
    }

    /**
     * 清除 Site 的一个权限来源。
     *
     * @param siteId Site 标识；由权威行或详情数据源绑定
     * @param expectedAuthorityRevision 用户看到的权限版本
     */
    public record AuthorityClearRequest(String siteId, long expectedAuthorityRevision) {
        /** 校验权威绑定。 */
        public AuthorityClearRequest {
            siteId = boundedText(siteId, "siteId", MAXIMUM_ID_LENGTH);
            if (expectedAuthorityRevision < 1) {
                throw new IllegalArgumentException("expectedAuthorityRevision must be positive");
            }
        }
    }

    private static String boundedText(String value, String name, int maximumLength) {
        String checked = ContractValidation.text(value, name);
        if (checked.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds its length limit");
        }
        return checked;
    }
}
