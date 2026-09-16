package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;

/** 网站多账号的脱敏契约；密码、Cookie 和 Vault 引用不进入这些公开投影。 */
public final class SiteAccountContracts {
    /** 账号管理通过现有隔离服务入口调用。 */
    public static final String SERVICE = "site.account";

    /** 用户名与密码密封包的专属 Vault 命名空间。 */
    public static final String LOGIN_NAMESPACE = "site-login";

    private SiteAccountContracts() {}

    /**
     * 宿主解析的账号所有权；不得用模型提供的 Workspace 替代调用上下文。
     *
     * @param workspaceId 所属 Workspace
     * @param siteId 所属网站
     * @param accountId 网站内账号 ID
     */
    public record AccountScope(WorkspaceId workspaceId, String siteId, String accountId) {
        /** 校验完整所有权。 */
        public AccountScope {
            Objects.requireNonNull(workspaceId, "workspaceId");
            siteId = identifier(siteId, "siteId");
            accountId = identifier(accountId, "accountId");
        }
    }

    /**
     * 网站内的账号选择；Workspace 始终由请求上下文提供。
     *
     * @param siteId 网站 ID
     * @param accountId 账号 ID
     */
    public record Selection(String siteId, String accountId) {
        /** 校验账号选择。 */
        public Selection {
            siteId = identifier(siteId, "siteId");
            accountId = identifier(accountId, "accountId");
        }
    }

    /**
     * 用户确认密码更新时的公开元数据，不包含用户名或密码。
     *
     * @param selection 精确账号
     * @param expectedSecurityRevision 用户确认的安全版本
     * @param expectedSiteAuthorityRevision 用户确认的网站 Origin 与权限版本
     */
    public record CredentialRequest(
            Selection selection, long expectedSecurityRevision, long expectedSiteAuthorityRevision) {
        /** 校验安全版本。 */
        public CredentialRequest {
            Objects.requireNonNull(selection, "selection");
            positive(expectedSecurityRevision, "expectedSecurityRevision");
            positive(expectedSiteAuthorityRevision, "expectedSiteAuthorityRevision");
        }
    }

    /**
     * Site 扩展向宿主发送的账号管理任务。
     *
     * @param operation 账号操作名
     * @param payload 非敏感管理内容
     * @param idempotencyKey 写命令幂等键，查询为空字符串
     * @param expectedRevision 账号期望版本
     */
    public record ServiceRequest(
            String operation, CanonicalPayload payload, String idempotencyKey, long expectedRevision) {
        /** 校验服务任务不携带秘密。 */
        public ServiceRequest {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        }
    }

    /**
     * 查询一个网站账号列表。
     *
     * @param siteId 网站 ID
     */
    public record ListRequest(String siteId) {
        /** 校验网站 ID。 */
        public ListRequest {
            siteId = identifier(siteId, "siteId");
        }
    }

    /**
     * 创建没有秘密的账号；账号 ID 和版本由服务端分配。
     *
     * @param siteId 网站 ID
     * @param name 用户指定的账号名称，不要求提供真实用户名
     */
    public record CreateRequest(String siteId, String name) {
        /** 校验账号名称。 */
        public CreateRequest {
            siteId = identifier(siteId, "siteId");
            name = text(name, "name", 200);
        }
    }

    /**
     * 更新账号展示名称及启用状态。
     *
     * @param selection 精确账号
     * @param name 用户指定名称
     * @param enabled 是否允许绑定新会话
     */
    public record UpdateRequest(Selection selection, String name, boolean enabled) {
        /** 校验非敏感管理内容。 */
        public UpdateRequest {
            Objects.requireNonNull(selection, "selection");
            name = text(name, "name", 200);
        }
    }

    /**
     * SDK、管理界面与模型允许读取的账号状态。
     *
     * @param accountId 账号 ID
     * @param siteId 网站 ID
     * @param revision 管理文档版本
     * @param securityRevision 密码、禁用或注销改变时递增的安全版本
     * @param stateRevision 登录态保存版本，零表示从未保存
     * @param name 用户指定的展示名称
     * @param enabled 是否启用
     * @param defaultAccount 是否是网站的明确默认账号
     * @param passwordConfigured 是否已保存用户名密码
     * @param loginStateConfigured 是否已保存登录态；不保证网站仍接受该登录态
     * @param updatedAt 最近变更时间
     */
    public record AccountProjection(
            String accountId,
            String siteId,
            long revision,
            long securityRevision,
            long stateRevision,
            String name,
            boolean enabled,
            boolean defaultAccount,
            boolean passwordConfigured,
            boolean loginStateConfigured,
            Instant updatedAt) {
        /** 校验脱敏状态。 */
        public AccountProjection {
            accountId = identifier(accountId, "accountId");
            siteId = identifier(siteId, "siteId");
            positive(revision, "revision");
            positive(securityRevision, "securityRevision");
            if (stateRevision < 0) {
                throw new IllegalArgumentException("stateRevision must not be negative");
            }
            name = text(name, "name", 200);
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /**
     * 两份账号秘密最后成功保存的时间；只投影 Vault 元数据，不读取秘密内容。
     *
     * @param passwordSavedAt 用户名密码最后保存时间，未配置时为空
     * @param loginStateSavedAt 登录态最后保存时间，未配置或已注销时为空
     */
    public record AccountSavedTimes(Optional<Instant> passwordSavedAt, Optional<Instant> loginStateSavedAt) {
        /** 不使用账号重命名时间代替秘密保存时间。 */
        public AccountSavedTimes {
            Objects.requireNonNull(passwordSavedAt, "passwordSavedAt");
            Objects.requireNonNull(loginStateSavedAt, "loginStateSavedAt");
        }
    }

    /**
     * 网站账号的完整有界列表。
     *
     * @param accounts 不含秘密的账号投影
     * @param savedTimes 以账号 ID 为键的秘密保存时间；旧响应未提供时为空映射
     */
    public record AccountList(List<AccountProjection> accounts, Map<String, AccountSavedTimes> savedTimes) {
        /** 复制列表和元数据，时间只能属于同一响应中的账号。 */
        public AccountList {
            accounts = List.copyOf(accounts);
            savedTimes = savedTimes == null ? Map.of() : Map.copyOf(savedTimes);
            var identifiers =
                    accounts.stream().map(AccountProjection::accountId).toList();
            if (!identifiers.containsAll(savedTimes.keySet())) {
                throw new IllegalArgumentException("保存时间不属于当前账号列表");
            }
        }

        /**
         * 兼容未提供保存时间的调用方。
         *
         * @param accounts 不含秘密的账号投影
         */
        public AccountList(List<AccountProjection> accounts) {
            this(accounts, Map.of());
        }
    }

    /**
     * 唯一登录态写入租约；只供宿主服务使用，不能由模型自行构造授权。
     *
     * @param scope 完整账号所有权
     * @param sessionId 获得保存权的浏览器会话
     * @param securityRevision 绑定时的账号安全版本
     * @param stateRevision 上次确认的登录态版本
     * @param siteAuthorityRevision 绑定时的网站安全版本
     * @param generation 当前宿主分配的租约代次
     */
    public record StateLease(
            AccountScope scope,
            String sessionId,
            long securityRevision,
            long stateRevision,
            long siteAuthorityRevision,
            long generation) {
        /** 校验保存租约。 */
        public StateLease {
            Objects.requireNonNull(scope, "scope");
            sessionId = text(sessionId, "sessionId", 200);
            positive(securityRevision, "securityRevision");
            positive(siteAuthorityRevision, "siteAuthorityRevision");
            positive(generation, "generation");
            if (stateRevision < 0) {
                throw new IllegalArgumentException("stateRevision must not be negative");
            }
        }
    }

    private static String identifier(String value, String name) {
        return text(value, name, 100);
    }

    private static String text(String value, String name, int limit) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty() || checked.length() > limit) {
            throw new IllegalArgumentException(name + " is outside its length limit");
        }
        return checked;
    }

    private static void positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
