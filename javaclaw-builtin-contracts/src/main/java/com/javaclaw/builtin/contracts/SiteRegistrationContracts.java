package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;

/** 人工网站登记的脱敏契约；临时 Context 只归属 Workspace，秘密不得进入任何公开记录。 */
public final class SiteRegistrationContracts {
    /** Site 扩展调用的唯一登记宿主服务。 */
    public static final String SERVICE = "site.registration";

    private SiteRegistrationContracts() {}

    /** 登记会话状态；只有 ACTIVE 允许继续授权或保存。 */
    public enum State {
        ACTIVE,
        COMPLETED,
        CANCELLED,
        EXPIRED,
        FAILED
    }

    /**
     * @param operation 登记命令或查询名
     * @param payload 脱敏参数
     * @param idempotencyKey 写操作幂等键；查询为空
     */
    public record ServiceRequest(String operation, CanonicalPayload payload, Optional<String> idempotencyKey) {
        /** 校验完整路由参数。 */
        public ServiceRequest {
            operation = text(operation, 100, "operation");
            Objects.requireNonNull(payload, "payload");
            idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey")
                    .map(value -> text(value, 200, "idempotencyKey"));
        }
    }

    /** @param uri 用户明确输入的初始 HTTPS 地址，可包含路径与查询，不能包含用户名密码 */
    public record BeginRequest(URI uri) {
        /** 校验可浏览地址。 */
        public BeginRequest {
            uri = https(uri);
        }
    }

    /** @param sessionId 宿主生成的登记 UUID */
    public record SessionRequest(String sessionId) {
        /** 拒绝空或伪造格式的会话身份。 */
        public SessionRequest {
            sessionId = session(sessionId);
        }
    }

    /**
     * @param sessionId 登记 UUID
     * @param expectedGeneration 用户看到的授权代次
     * @param origin 明确追加的精确 HTTPS 来源
     */
    public record OriginRequest(String sessionId, long expectedGeneration, URI origin) {
        /** 来源不得包含路径、查询或片段。 */
        public OriginRequest {
            sessionId = session(sessionId);
            positive(expectedGeneration, "expectedGeneration");
            origin = exactOrigin(origin);
        }
    }

    /**
     * @param sessionId 登记 UUID
     * @param expectedGeneration 用户确认的授权代次
     * @param expectedPageRevision 用户确认的当前页面版本
     * @param credentialId 可选同 Origin 密码候选 ID，不包含输入值
     * @param name 用户指定网站名；不能为空
     */
    public record CompleteRequest(
            String sessionId,
            long expectedGeneration,
            long expectedPageRevision,
            Optional<String> credentialId,
            String name) {
        /** 校验保存确认，页面与密码的实际所属来源由 Worker 再次检查。 */
        public CompleteRequest {
            sessionId = session(sessionId);
            positive(expectedGeneration, "expectedGeneration");
            positive(expectedPageRevision, "expectedPageRevision");
            credentialId =
                    Objects.requireNonNull(credentialId, "credentialId").map(value -> text(value, 200, "credentialId"));
            name = text(name, 200, "name");
        }
    }

    /**
     * @param id 临时候选身份
     * @param origin 密码所属精确来源
     * @param label 不含用户名密码值的描述
     */
    public record CredentialCandidate(String id, URI origin, String label) {
        /** 候选只传身份和脱敏描述。 */
        public CredentialCandidate {
            id = text(id, 200, "id");
            origin = exactOrigin(origin);
            label = text(label, 300, "label");
        }
    }

    /**
     * @param generation 授权代次
     * @param allowedOrigins 已授权来源
     * @param pendingOrigins 被阻断来源
     * @param expiresAt 到期时刻
     */
    public record Access(long generation, Set<URI> allowedOrigins, Set<URI> pendingOrigins, Instant expiresAt) {
        /** 固定来源集合，发现来源不会自动授权。 */
        public Access {
            positive(generation, "generation");
            allowedOrigins = origins(allowedOrigins);
            pendingOrigins = origins(pendingOrigins);
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * @param pageRevision 当前页面版本；尚未读取为零
     * @param uri 去掉查询和片段的地址
     * @param title 页面标题
     * @param candidates 密码候选
     */
    public record Page(long pageRevision, Optional<URI> uri, String title, List<CredentialCandidate> candidates) {
        /** 不接受带查询、片段或用户信息的可展示地址。 */
        public Page {
            if (pageRevision < 0) {
                throw new IllegalArgumentException("pageRevision must not be negative");
            }
            uri = Objects.requireNonNull(uri, "uri").map(value -> {
                URI checked = https(value);
                if (checked.getRawQuery() != null || checked.getRawFragment() != null) {
                    throw new IllegalArgumentException("registration page URI must omit query and fragment");
                }
                return checked;
            });
            title = Objects.requireNonNull(title, "title");
            if (title.length() > 1000) {
                throw new IllegalArgumentException("registration title exceeds limit");
            }
            candidates = List.copyOf(candidates);
            if (candidates.size() > 20
                    || candidates.stream()
                                    .map(CredentialCandidate::id)
                                    .distinct()
                                    .count()
                            != candidates.size()) {
                throw new IllegalArgumentException("invalid credential candidates");
            }
        }
    }

    /**
     * @param siteId 新网站 ID
     * @param accountId 新默认账号 ID
     * @param origin 保存网站的精确来源
     */
    public record Completed(String siteId, String accountId, URI origin) {
        /** 不携带 Vault 引用或秘密。 */
        public Completed {
            siteId = text(siteId, 200, "siteId");
            accountId = text(accountId, 200, "accountId");
            origin = exactOrigin(origin);
        }
    }

    /**
     * @param sessionId 登记 UUID
     * @param state 会话状态
     * @param access 授权边界
     * @param page 脱敏页面
     * @param completed 已提交结果
     */
    public record Session(String sessionId, State state, Access access, Page page, Optional<Completed> completed) {
        /** 完成状态必须带持久保存结果，其他状态不得伪造完成。 */
        public Session {
            sessionId = session(sessionId);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(page, "page");
            completed = Objects.requireNonNull(completed, "completed");
            if ((state == State.COMPLETED) != completed.isPresent()) {
                throw new IllegalArgumentException("registration completion state mismatch");
            }
        }
    }

    /**
     * @param sessionId 登记 UUID
     * @param state Worker 状态
     * @param access Worker 当前租约
     * @param page 脱敏页面
     */
    public record WorkerStatus(String sessionId, State state, Access access, Page page) {
        /** Worker 不能自行宣布数据库已完成登记。 */
        public WorkerStatus {
            sessionId = session(sessionId);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(page, "page");
            if (state == State.COMPLETED) {
                throw new IllegalArgumentException("Worker cannot commit registration");
            }
        }
    }

    /**
     * @param sessionId 宿主 UUID
     * @param workspaceId 唯一 Workspace
     * @param initialUri 用户输入地址
     * @param lease 人工短期租约
     */
    public record WorkerTask(
            String sessionId, WorkspaceId workspaceId, URI initialUri, BrowserContracts.AccessLease lease) {
        /** 独立登记 Context 不具有 Thread 或助手执行身份。 */
        public WorkerTask {
            sessionId = session(sessionId);
            Objects.requireNonNull(workspaceId, "workspaceId");
            initialUri = https(initialUri);
            Objects.requireNonNull(lease, "lease");
            if (lease.mode() != BrowserContracts.ControlMode.HUMAN
                    || !lease.allowedOrigins().contains(SiteContracts.originOf(displayUri(initialUri)))) {
                throw new IllegalArgumentException("registration requires HUMAN lease for initial origin");
            }
        }
    }

    /**
     * @param value HTTPS 地址
     * @return 去查询与片段后的可展示地址
     */
    public static URI displayUri(URI value) {
        URI checked = https(value);
        // 保留原始转义，避免把路径中的 %2F 或 %3F 误改成新的路径/查询分隔符。
        return URI.create(checked.getScheme() + "://" + checked.getRawAuthority() + checked.getRawPath());
    }

    private static URI https(URI value) {
        URI checked = Objects.requireNonNull(value, "uri").normalize();
        if (!"https".equalsIgnoreCase(checked.getScheme())
                || checked.getHost() == null
                || checked.getUserInfo() != null
                || checked.toASCIIString().length() > 4096
                || checked.getPort() == 0
                || checked.getPort() > 65_535) {
            throw new IllegalArgumentException("registration requires bounded HTTPS URI without credentials");
        }
        return checked;
    }

    private static URI exactOrigin(URI value) {
        URI checked = https(value);
        URI origin = SiteContracts.originOf(checked);
        if (!checked.equals(origin)) {
            throw new IllegalArgumentException("registration origin must be exact");
        }
        return origin;
    }

    private static Set<URI> origins(Set<URI> values) {
        Set<URI> checked = Set.copyOf(values);
        if (checked.size() > 128) {
            throw new IllegalArgumentException("registration origin limit exceeded");
        }
        checked.forEach(SiteRegistrationContracts::exactOrigin);
        return checked;
    }

    private static String session(String value) {
        return UUID.fromString(text(value, 36, "sessionId")).toString();
    }

    private static String text(String value, int limit, String field) {
        String checked = ContractValidation.text(value, field);
        if (checked.length() > limit) {
            throw new IllegalArgumentException(field + " exceeds limit");
        }
        return checked;
    }

    private static void positive(long value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
