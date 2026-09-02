package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantRef;

import static com.javaclaw.builtin.contracts.SiteContractValidation.boundedText;
import static com.javaclaw.builtin.contracts.SiteContractValidation.checkedTimeout;
import static com.javaclaw.builtin.contracts.SiteContractValidation.errorCode;
import static com.javaclaw.builtin.contracts.SiteContractValidation.maximumCharacters;
import static com.javaclaw.builtin.contracts.SiteContractValidation.normalizeOrigin;
import static com.javaclaw.builtin.contracts.SiteContractValidation.requireAllowed;
import static com.javaclaw.builtin.contracts.SiteContractValidation.requireHttpsPage;
import static com.javaclaw.builtin.contracts.SiteContractValidation.sha256;
import static com.javaclaw.builtin.contracts.SiteContractValidation.uuid;

/** Site/Browser 扩展公开契约；所有网络权限都以精确 HTTPS Origin 表达。 */
public final class SiteContracts {
    /** Site 扩展调用受控 Browser 快照服务的标识。 */
    public static final String BROWSER_SNAPSHOT_SERVICE = "browser.snapshot";

    /** Site 权限变化后终止旧 Browser 会话的服务标识。 */
    public static final String BROWSER_INVALIDATE_SERVICE = "browser.invalidate";

    /** 启动隔离人工登录会话的服务标识。 */
    public static final String BROWSER_LOGIN_BEGIN_SERVICE = "browser.login.begin";

    /** 读取隔离人工登录会话状态的服务标识。 */
    public static final String BROWSER_LOGIN_STATUS_SERVICE = "browser.login.status";

    /** 列出当前 Workspace 人工登录会话的服务标识。 */
    public static final String BROWSER_LOGIN_LIST_SERVICE = "browser.login.list";

    /** 保存人工登录产生的 Browser storage state 的服务标识。 */
    public static final String BROWSER_LOGIN_SAVE_SERVICE = "browser.login.save";

    /** 取消人工登录会话的服务标识。 */
    public static final String BROWSER_LOGIN_CANCEL_SERVICE = "browser.login.cancel";

    /** Browser storage state 在 Vault 中使用的固定命名空间。 */
    public static final String BROWSER_CREDENTIAL_NAMESPACE = "browser";

    /** Site HTTP 凭据在 Vault 中使用的固定命名空间。 */
    public static final String SITE_CREDENTIAL_NAMESPACE = "site";

    private SiteContracts() {}

    /** Site 凭据的注入方式；Secret 本身永远不进入该契约。 */
    public enum CredentialKind {
        /** 不使用凭据。 */
        NONE,
        /** 仅向主 Origin 注入 Bearer Authorization。 */
        BEARER,
        /** 仅向主 Origin 注入一个受校验的自定义请求头。 */
        API_KEY_HEADER,
        /** 将 Vault 中的 Browser storage state 直接交给隔离 Context。 */
        BROWSER_STORAGE
    }

    /**
     * Site 的脱敏凭据绑定。
     *
     * @param kind 凭据用途
     * @param reference Vault opaque 引用；NONE 时为空
     * @param apiKeyHeader API Key header 名；仅 API_KEY_HEADER 时存在
     */
    public record SiteCredential(
            CredentialKind kind, Optional<CredentialRef> reference, Optional<String> apiKeyHeader) {
        private static final Set<String> FORBIDDEN_HEADERS = Set.of(
                "authorization",
                "connection",
                "content-length",
                "cookie",
                "host",
                "proxy-authorization",
                "set-cookie",
                "transfer-encoding",
                "upgrade");

        /** 校验模式、命名空间和 header。 */
        public SiteCredential {
            Objects.requireNonNull(kind, "kind");
            reference = Objects.requireNonNull(reference, "reference");
            apiKeyHeader = Objects.requireNonNull(apiKeyHeader, "apiKeyHeader")
                    .map(value -> headerName(value, "apiKeyHeader"));
            switch (kind) {
                case NONE -> requireEmpty(reference, apiKeyHeader);
                case BEARER -> requireReference(reference, SITE_CREDENTIAL_NAMESPACE, apiKeyHeader, false);
                case API_KEY_HEADER -> requireReference(reference, SITE_CREDENTIAL_NAMESPACE, apiKeyHeader, true);
                case BROWSER_STORAGE -> requireReference(reference, BROWSER_CREDENTIAL_NAMESPACE, apiKeyHeader, false);
            }
        }

        /** 创建无凭据绑定。 */
        public static SiteCredential none() {
            return new SiteCredential(CredentialKind.NONE, Optional.empty(), Optional.empty());
        }

        private static void requireEmpty(Optional<CredentialRef> reference, Optional<String> header) {
            if (reference.isPresent() || header.isPresent()) {
                throw new IllegalArgumentException("NONE credential must not contain a reference or header");
            }
        }

        private static void requireReference(
                Optional<CredentialRef> reference, String namespace, Optional<String> header, boolean headerRequired) {
            CredentialRef value =
                    reference.orElseThrow(() -> new IllegalArgumentException("credential reference is required"));
            if (!namespace.equals(value.namespace()) || header.isPresent() != headerRequired) {
                throw new IllegalArgumentException("credential namespace or header does not match its kind");
            }
        }

        private static String headerName(String value, String name) {
            String normalized = ContractValidation.text(value, name).toLowerCase(java.util.Locale.ROOT);
            if (!normalized.matches("[!#$%&'*+.^_`|~0-9a-z-]{1,80}") || FORBIDDEN_HEADERS.contains(normalized)) {
                throw new IllegalArgumentException(name + " is not an allowed HTTP header");
            }
            return normalized;
        }
    }

    /**
     * 受限站点配置。
     *
     * <p>{@code authorityRevision} 只在 Origin、凭据或私网授权变化时递增。Browser 会话绑定该值，因此名称等展示字段变化不会误杀会话，任何权限来源变化又能立即终止旧会话。
     *
     * @param id Site 标识
     * @param revision 文档版本
     * @param authorityRevision 权限来源版本，不大于 revision
     * @param name 名称
     * @param origin 主 HTTPS Origin；凭据只可发送至此 Origin
     * @param allowedOrigins 导航、重定向和子资源允许的精确 HTTPS Origin
     * @param credential 脱敏凭据引用
     * @param privateNetworkGrant 可选的精确私网授权版本
     * @param enabled 是否可用于新调用
     * @param updatedAt 更新时间
     */
    public record Site(
            String id,
            long revision,
            long authorityRevision,
            String name,
            URI origin,
            Set<URI> allowedOrigins,
            SiteCredential credential,
            Optional<PrivateNetworkGrantRef> privateNetworkGrant,
            boolean enabled,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验 Site 权限快照。 */
        public Site {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            authorityRevision = ContractValidation.revision(authorityRevision);
            if (authorityRevision > revision) {
                throw new IllegalArgumentException("authorityRevision must not exceed revision");
            }
            name = ContractValidation.text(name, "name");
            origin = normalizeOrigin(origin);
            TreeSet<URI> normalized = new TreeSet<>(java.util.Comparator.comparing(URI::toASCIIString));
            Objects.requireNonNull(allowedOrigins, "allowedOrigins")
                    .forEach(value -> normalized.add(normalizeOrigin(value)));
            if (normalized.isEmpty() || !normalized.contains(origin)) {
                throw new IllegalArgumentException("allowedOrigins must contain the primary origin");
            }
            allowedOrigins = Collections.unmodifiableSet(normalized);
            credential = Objects.requireNonNull(credential, "credential");
            privateNetworkGrant = Objects.requireNonNull(privateNetworkGrant, "privateNetworkGrant");
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
        }
    }

    /**
     * 对 SDK、ViewSchema 与模型 Tool 可见的脱敏 Site 投影。
     *
     * <p>本投影绝不携带 CredentialRef 或 PrivateNetworkGrantRef；布尔状态只能用于展示，不能作为调用授权。
     *
     * @param id Site 标识
     * @param revision 文档版本
     * @param authorityRevision 权限来源版本
     * @param name 名称
     * @param origin 主 HTTPS Origin
     * @param allowedOrigins 允许的精确 HTTPS Origin
     * @param hasCredential 是否配置凭据
     * @param hasPrivateGrant 是否配置私网授权
     * @param enabled 是否允许新调用
     * @param updatedAt 更新时间
     */
    public record Projection(
            String id,
            long revision,
            long authorityRevision,
            String name,
            URI origin,
            Set<URI> allowedOrigins,
            boolean hasCredential,
            boolean hasPrivateGrant,
            boolean enabled,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 复用 Site 不变量，并复制脱敏集合。 */
        public Projection {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            authorityRevision = ContractValidation.revision(authorityRevision);
            if (authorityRevision > revision) {
                throw new IllegalArgumentException("authorityRevision must not exceed revision");
            }
            name = ContractValidation.text(name, "name");
            origin = normalizeOrigin(origin);
            TreeSet<URI> normalized = new TreeSet<>(java.util.Comparator.comparing(URI::toASCIIString));
            Objects.requireNonNull(allowedOrigins, "allowedOrigins")
                    .forEach(value -> normalized.add(normalizeOrigin(value)));
            if (normalized.isEmpty() || !normalized.contains(origin)) {
                throw new IllegalArgumentException("allowedOrigins must contain the primary origin");
            }
            allowedOrigins = Collections.unmodifiableSet(normalized);
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
        }

        /**
         * 从内部权威 Site 生成不含 opaque 引用的投影。
         *
         * @param site 内部 Site
         * @return 脱敏投影
         */
        public static Projection from(Site site) {
            Site value = Objects.requireNonNull(site, "site");
            return new Projection(
                    value.id(),
                    value.revision(),
                    value.authorityRevision(),
                    value.name(),
                    value.origin(),
                    value.allowedOrigins(),
                    value.credential().kind() != CredentialKind.NONE,
                    value.privateNetworkGrant().isPresent(),
                    value.enabled(),
                    value.updatedAt());
        }
    }

    /**
     * Site 发现条件。
     *
     * @param query 名称或 Origin 中的文本
     * @param limit 最大返回数，范围 1–100
     */
    public record SearchRequest(String query, int limit) {
        /** 校验检索条件。 */
        public SearchRequest {
            query = ContractValidation.text(query, "query");
            limit = ContractValidation.searchLimit(limit);
        }
    }

    /**
     * Site 发现结果。
     *
     * @param matches 按更新时间倒序排列的启用站点
     */
    public record SearchResult(List<Projection> matches) {
        /** 复制结果列表。 */
        public SearchResult {
            matches = List.copyOf(matches);
        }
    }

    /**
     * 请求读取一个冻结到 Site 与 authority revision 的页面快照。
     *
     * @param siteId Site 标识
     * @param expectedRevision 调用方确认过的 Site revision
     * @param expectedAuthorityRevision 调用方冻结的权限来源 revision
     * @param uri 要访问的 HTTPS URI
     * @param maxCharacters 正文最大字符数，范围 1–200000
     */
    public record SnapshotRequest(
            String siteId, long expectedRevision, long expectedAuthorityRevision, URI uri, int maxCharacters) {
        /** 校验快照请求。 */
        public SnapshotRequest {
            siteId = ContractValidation.text(siteId, "siteId");
            expectedRevision = ContractValidation.revision(expectedRevision);
            expectedAuthorityRevision = ContractValidation.revision(expectedAuthorityRevision);
            uri = requireHttpsPage(uri, "uri");
            maxCharacters = maximumCharacters(maxCharacters);
        }
    }

    /**
     * Site 扩展交给 App Server 隔离服务的权威调用快照。
     *
     * @param site 精确 Site 版本
     * @param uri 初始页面 URI
     * @param maxCharacters 正文上限
     * @param timeout 单次导航上限
     */
    public record SnapshotTask(Site site, URI uri, int maxCharacters, Duration timeout) {
        /** 校验调用不可超出 Site 冻结权限。 */
        public SnapshotTask {
            site = Objects.requireNonNull(site, "site");
            uri = requireHttpsPage(uri, "uri");
            requireAllowed(uri, site.allowedOrigins());
            maxCharacters = maximumCharacters(maxCharacters);
            timeout = checkedTimeout(timeout, Duration.ofSeconds(60));
        }
    }

    /**
     * Browser Worker 返回的只读页面快照。
     *
     * @param uri 导航完成后的最终 URI
     * @param title 页面标题，可为空字符串
     * @param text 截断后的可见正文，可为空字符串
     * @param capturedAt Worker 捕获时间
     */
    public record PageSnapshot(URI uri, String title, String text, Instant capturedAt) {
        /** 校验页面快照。 */
        public PageSnapshot {
            uri = requireHttpsPage(uri, "uri");
            title = Objects.requireNonNull(title, "title").strip();
            text = Objects.requireNonNull(text, "text");
            capturedAt = ContractValidation.instant(capturedAt, "capturedAt");
        }
    }

    /**
     * 请求终止不再匹配 Site authority 的活动 Browser 会话。
     *
     * @param siteId Site 标识
     * @param currentAuthorityRevision 当前 authority revision；删除时为 0
     */
    public record AuthorityInvalidation(String siteId, long currentAuthorityRevision) {
        /** 校验失效通知。 */
        public AuthorityInvalidation {
            siteId = ContractValidation.text(siteId, "siteId");
            if (currentAuthorityRevision < 0) {
                throw new IllegalArgumentException("currentAuthorityRevision must not be negative");
            }
        }
    }

    /** 人工登录会话状态；终态不会重新进入可保存状态。 */
    public enum LoginSessionState {
        /** 原生 Sandbox 与 Chromium 正在启动。 */
        STARTING,
        /** 隔离 Chromium 窗口已打开，可以由用户操作。 */
        READY,
        /** 正在从 Worker 私有管道保存 storage state。 */
        SAVING,
        /** storage state 已直接密封进 Vault。 */
        SAVED,
        /** 用户主动取消或 Site authority 变化。 */
        CANCELLED,
        /** 十分钟硬时限已到。 */
        EXPIRED,
        /** Worker 或安全校验失败。 */
        FAILED
    }

    /**
     * 开始人工登录的公开命令。
     *
     * @param siteId Site 标识
     * @param expectedRevision 用户看到的 Site revision
     * @param expectedAuthorityRevision 用户看到的 authority revision
     */
    public record LoginBeginRequest(String siteId, long expectedRevision, long expectedAuthorityRevision) {
        /** 校验冻结版本。 */
        public LoginBeginRequest {
            siteId = ContractValidation.text(siteId, "siteId");
            expectedRevision = ContractValidation.revision(expectedRevision);
            expectedAuthorityRevision = ContractValidation.revision(expectedAuthorityRevision);
            if (expectedAuthorityRevision > expectedRevision) {
                throw new IllegalArgumentException("expectedAuthorityRevision must not exceed expectedRevision");
            }
        }
    }

    /**
     * 登录会话控制请求。
     *
     * @param sessionId 随机会话标识
     */
    public record LoginControlRequest(String sessionId) {
        /** 校验 UUID 会话标识，避免其被解释为文件名或路径。 */
        public LoginControlRequest {
            sessionId = uuid(sessionId, "sessionId");
        }
    }

    /**
     * 扩展提交给 App Server 的登录启动任务。
     *
     * @param site 已冻结且启用的 Site
     * @param sessionId 从幂等键派生的稳定 UUID
     * @param timeout 登录硬时限，最多十分钟
     */
    public record LoginBeginTask(Site site, String sessionId, Duration timeout) {
        /** 校验 Site authority 与登录时限。 */
        public LoginBeginTask {
            site = Objects.requireNonNull(site, "site");
            sessionId = uuid(sessionId, "sessionId");
            timeout = checkedTimeout(timeout, Duration.ofMinutes(10));
        }
    }

    /**
     * 登录保存任务；幂等身份由扩展命令原样传入，Secret 不进入该对象。
     *
     * @param sessionId 会话标识
     * @param idempotencyKey 扩展命令幂等键
     * @param requestDigest 非敏感请求摘要
     */
    public record LoginSaveTask(String sessionId, String idempotencyKey, String requestDigest) {
        /** 校验会话与持久命令身份。 */
        public LoginSaveTask {
            sessionId = uuid(sessionId, "sessionId");
            idempotencyKey = boundedText(idempotencyKey, "idempotencyKey", 200);
            requestDigest = sha256(requestDigest, "requestDigest");
        }
    }

    /**
     * 不含 Cookie、storage state、页面 URL 或截图的登录会话投影。
     *
     * @param sessionId 会话标识
     * @param siteId Site 标识
     * @param siteRevision 启动时 Site revision
     * @param authorityRevision 启动时 authority revision
     * @param state 当前状态
     * @param startedAt 启动时间
     * @param expiresAt 不晚于启动后十分钟的到期时间
     * @param failureCode 可选的脱敏稳定错误码
     */
    public record LoginSession(
            String sessionId,
            String siteId,
            long siteRevision,
            long authorityRevision,
            LoginSessionState state,
            Instant startedAt,
            Instant expiresAt,
            Optional<String> failureCode) {
        /** 校验投影不携带敏感自由文本。 */
        public LoginSession {
            sessionId = uuid(sessionId, "sessionId");
            siteId = ContractValidation.text(siteId, "siteId");
            siteRevision = ContractValidation.revision(siteRevision);
            authorityRevision = ContractValidation.revision(authorityRevision);
            state = Objects.requireNonNull(state, "state");
            startedAt = ContractValidation.instant(startedAt, "startedAt");
            expiresAt = ContractValidation.instant(expiresAt, "expiresAt");
            if (expiresAt.isBefore(startedAt) || expiresAt.isAfter(startedAt.plus(Duration.ofMinutes(10)))) {
                throw new IllegalArgumentException("login session expiry is outside the ten minute limit");
            }
            failureCode =
                    Objects.requireNonNull(failureCode, "failureCode").map(value -> errorCode(value, "failureCode"));
        }
    }

    /**
     * Workspace 登录会话列表。
     *
     * @param sessions 仅含脱敏状态投影
     * @param interactiveLoginAvailable 当前发行镜像是否通过原生登录能力验证
     */
    public record LoginSessionList(List<LoginSession> sessions, boolean interactiveLoginAvailable) {
        /** 复制投影列表。 */
        public LoginSessionList {
            sessions = List.copyOf(Objects.requireNonNull(sessions, "sessions"));
        }
    }

    /**
     * 隔离 Browser 服务与 Site 扩展之间的内部提交回执。
     *
     * <p>该类型包含 opaque CredentialRef，只能通过进程内 {@code IsolatedServicePort} 返回给内置 Site 扩展，不得直接返回 SDK。
     *
     * @param site 已原子切换到新 authority revision 的 Site
     * @param credential 只含 opaque 引用的 Vault 元数据
     * @param session 不含浏览内容的终态投影
     */
    public record LoginSaveCommit(Site site, CredentialMetadata credential, LoginSession session) {
        /** 校验引用命名空间和终态。 */
        public LoginSaveCommit {
            site = Objects.requireNonNull(site, "site");
            credential = Objects.requireNonNull(credential, "credential");
            session = Objects.requireNonNull(session, "session");
            if (!BROWSER_CREDENTIAL_NAMESPACE.equals(credential.reference().namespace())
                    || session.state() != LoginSessionState.SAVED
                    || !site.id().equals(session.siteId())
                    || site.credential().kind() != CredentialKind.BROWSER_STORAGE
                    || !site.credential().reference().orElseThrow().equals(credential.reference())) {
                throw new IllegalArgumentException("login save result does not match Browser credential authority");
            }
        }
    }

    /**
     * 可安全返回 SDK 的登录保存回执。
     *
     * <p>只显示“已配置”状态，不包含 CredentialRef、PrivateNetworkGrantRef 或 Browser storage state。
     *
     * @param site 不含 opaque 引用的 Site 投影
     * @param credentialConfigured Browser 凭据是否已配置
     * @param session 不含浏览内容的终态投影
     */
    public record LoginSaveResult(Projection site, boolean credentialConfigured, LoginSession session) {
        /** 校验投影与终态一致。 */
        public LoginSaveResult {
            site = Objects.requireNonNull(site, "site");
            session = Objects.requireNonNull(session, "session");
            if (!credentialConfigured
                    || !site.hasCredential()
                    || session.state() != LoginSessionState.SAVED
                    || !site.id().equals(session.siteId())
                    || site.revision() != session.siteRevision() + 1
                    || site.authorityRevision() != session.authorityRevision() + 1) {
                throw new IllegalArgumentException("public login save result does not match the saved authority");
            }
        }
    }

    /** 将页面 URI 规范化为可精确比较的 HTTPS Origin。 */
    public static URI originOf(URI page) {
        URI checked = requireHttpsPage(page, "page");
        try {
            return PrivateNetworkGrant.normalizeOrigin(
                    new URI("https", null, checked.getHost(), checked.getPort(), null, null, null));
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("page origin is invalid", failure);
        }
    }
}
