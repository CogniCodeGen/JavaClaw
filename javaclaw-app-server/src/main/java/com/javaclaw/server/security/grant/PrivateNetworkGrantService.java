package com.javaclaw.server.security.grant;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkGrantRef;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.PrivateNetworkGrantPort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.IdempotentCommandStore;
import com.javaclaw.server.persistence.PersistenceException;

/** 精确 Origin、DNS 集合与短时效私网授权服务。 */
public final class PrivateNetworkGrantService implements PrivateNetworkGrantPort {
    /** 未指定时使用的一小时有效期。 */
    public static final Duration DEFAULT_VALIDITY = Duration.ofHours(1);
    /** 私网授权的绝对时长上限。 */
    public static final Duration MAXIMUM_VALIDITY = Duration.ofHours(24);

    private final H2Transactions transactions;
    private final SecurityGrantRepository grants = new SecurityGrantRepository();
    private final IdempotentCommandStore commands = new IdempotentCommandStore();
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建私网授权服务。
     *
     * @param database data-v6 数据库
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public PrivateNetworkGrantService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 规范化并生成必须由用户确认的预览。
     *
     * @param workspaceId 所属 Workspace
     * @param purpose MCP 或 Site
     * @param origin 精确 HTTPS Origin
     * @param dnsAddresses 本次 DNS 解析得到的完整数字地址集合
     * @param validity 可选有效期；为空时一小时
     * @return 带内容摘要的规范化预览
     */
    public PrivateNetworkGrantPreview preview(
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> dnsAddresses,
            Optional<Duration> validity) {
        WorkspaceId checkedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        PrivateNetworkPurpose checkedPurpose = Objects.requireNonNull(purpose, "purpose");
        URI checkedOrigin = PrivateNetworkGrant.normalizeOrigin(origin);
        Set<String> checkedAddresses = PrivateNetworkAddressPolicy.requireGrantable(dnsAddresses);
        Duration duration = requireValidity(validity.orElse(DEFAULT_VALIDITY));
        Instant expiresAt = now().plus(duration);
        PreviewContent content =
                new PreviewContent(checkedWorkspace, checkedPurpose, checkedOrigin, checkedAddresses, expiresAt);
        return new PrivateNetworkGrantPreview(
                checkedWorkspace,
                checkedPurpose,
                checkedOrigin,
                checkedAddresses,
                expiresAt,
                json.encode(content).sha256());
    }

    /**
     * 提交已确认预览并创建不可变授权。
     *
     * @param identity expected revision 必须为 0
     * @param preview 用户确认的完整预览
     * @return 首个授权版本
     */
    public PrivateNetworkGrant create(CommandIdentity identity, PrivateNetworkGrantPreview preview) {
        CommandIdentity checkedIdentity = requireCreateIdentity(identity);
        PrivateNetworkGrantPreview checkedPreview = requireFreshPreview(preview);
        synchronized (GrantCommandLocks.forKey(checkedIdentity.idempotencyKey())) {
            return execute(connection -> {
                Optional<com.javaclaw.api.CanonicalPayload> recovered = commands.recover(connection, checkedIdentity);
                if (recovered.isPresent()) {
                    return json.decode(recovered.orElseThrow(), PrivateNetworkGrant.class);
                }
                requireWorkspace(connection, checkedPreview.workspaceId());
                Instant now = now();
                PrivateNetworkGrant grant = new PrivateNetworkGrant(
                        UUID.randomUUID().toString(),
                        1,
                        SecurityGrantState.ACTIVE,
                        checkedPreview.workspaceId(),
                        checkedPreview.purpose(),
                        checkedPreview.origin(),
                        checkedPreview.dnsAddresses(),
                        checkedPreview.expiresAt(),
                        now,
                        now);
                grants.insertGrant(connection, SecurityGrantRepository.GrantTable.PRIVATE_NETWORK, stored(grant));
                commands.record(connection, checkedIdentity, json.encode(grant), now);
                return grant;
            });
        }
    }

    /**
     * 写入不可逆撤销 tombstone；后续连接判断立即读取该版本。
     *
     * @param identity expected revision 必须匹配当前版本
     * @param grantId 授权标识
     * @return 撤销版本
     */
    public PrivateNetworkGrant revoke(CommandIdentity identity, String grantId) {
        CommandIdentity checkedIdentity = Objects.requireNonNull(identity, "identity");
        String checkedId = identifier(grantId);
        synchronized (GrantCommandLocks.forKey(checkedIdentity.idempotencyKey())) {
            return execute(connection -> {
                Optional<com.javaclaw.api.CanonicalPayload> recovered = commands.recover(connection, checkedIdentity);
                if (recovered.isPresent()) {
                    return json.decode(recovered.orElseThrow(), PrivateNetworkGrant.class);
                }
                PrivateNetworkGrant current = grants.latest(
                                connection, SecurityGrantRepository.GrantTable.PRIVATE_NETWORK, checkedId, true)
                        .map(this::decodePrivate)
                        .orElseThrow(() -> PersistenceException.invalidRequest("私网授权不存在"));
                requireRevocable(current.state(), current.revision(), checkedIdentity.expectedRevision());
                Instant now = now();
                PrivateNetworkGrant revoked = new PrivateNetworkGrant(
                        current.id(),
                        Math.addExact(current.revision(), 1),
                        SecurityGrantState.REVOKED,
                        current.workspaceId(),
                        current.purpose(),
                        current.origin(),
                        current.dnsAddresses(),
                        current.expiresAt(),
                        current.createdAt(),
                        now);
                grants.insertGrant(connection, SecurityGrantRepository.GrantTable.PRIVATE_NETWORK, stored(revoked));
                commands.record(connection, checkedIdentity, json.encode(revoked), now);
                return revoked;
            });
        }
    }

    /**
     * 列出 Workspace 中每个私网授权的最新版本。
     *
     * @param workspaceId Workspace
     * @return 按授权标识排序的快照
     */
    public List<PrivateNetworkGrant> listLatest(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return execute(connection ->
                grants.listLatest(connection, SecurityGrantRepository.GrantTable.PRIVATE_NETWORK, workspaceId).stream()
                        .map(this::decodePrivate)
                        .toList());
    }

    /**
     * 列出当前仍可绑定到指定用途的活动授权。
     *
     * @param workspaceId 所属 Workspace
     * @param purpose 授权用途
     * @return 按授权 ID 排序的活动、未过期授权
     */
    @Override
    public List<PrivateNetworkGrant> available(WorkspaceId workspaceId, PrivateNetworkPurpose purpose) {
        PrivateNetworkPurpose checkedPurpose = Objects.requireNonNull(purpose, "purpose");
        Instant now = now();
        return listLatest(workspaceId).stream()
                .filter(grant -> grant.purpose() == checkedPurpose)
                .filter(grant -> grant.state() == SecurityGrantState.ACTIVE)
                .filter(grant -> now.isBefore(grant.expiresAt()))
                .toList();
    }

    /**
     * 校验 Site 或 MCP 要保存的精确授权版本；执行连接时仍须重新校验 DNS 集合与实时撤权。
     *
     * @param reference 精确授权版本
     * @param workspaceId 当前 Workspace
     * @param purpose 当前用途
     * @param origin 要绑定的精确 Origin
     * @return 当前可绑定的权威授权
     */
    @Override
    public PrivateNetworkGrant requireBindable(
            PrivateNetworkGrantRef reference, WorkspaceId workspaceId, PrivateNetworkPurpose purpose, URI origin) {
        PrivateNetworkGrantRef checkedReference = Objects.requireNonNull(reference, "reference");
        WorkspaceId checkedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        PrivateNetworkPurpose checkedPurpose = Objects.requireNonNull(purpose, "purpose");
        URI checkedOrigin = PrivateNetworkGrant.normalizeOrigin(origin);
        PrivateNetworkGrant current = execute(connection -> grants.latest(
                        connection, SecurityGrantRepository.GrantTable.PRIVATE_NETWORK, checkedReference.id(), false)
                .map(this::decodePrivate)
                .orElseThrow(() -> PersistenceException.invalidRequest("私网授权不存在")));
        boolean valid = current.revision() == checkedReference.revision()
                && current.workspaceId().equals(checkedWorkspace)
                && current.purpose() == checkedPurpose
                && current.origin().equals(checkedOrigin)
                && current.state() == SecurityGrantState.ACTIVE
                && now().isBefore(current.expiresAt());
        if (!valid) {
            throw PersistenceException.invalidRequest("私网授权版本、范围或实时状态不允许绑定");
        }
        return current;
    }

    /**
     * 读取授权全部不可变版本。
     *
     * @param grantId 授权标识
     * @return revision 升序历史
     */
    public List<PrivateNetworkGrant> history(String grantId) {
        String checkedId = identifier(grantId);
        return execute(connection ->
                grants.history(connection, SecurityGrantRepository.GrantTable.PRIVATE_NETWORK, checkedId).stream()
                        .map(this::decodePrivate)
                        .toList());
    }

    /**
     * 在每次网络连接前重新判断冻结授权与当前实时状态。
     *
     * @param grantId 冻结授权标识
     * @param frozenRevision 冻结 revision
     * @param workspaceId 当前 Workspace
     * @param purpose 当前用途
     * @param origin 当前精确 Origin
     * @param dnsAddresses 当前完整 DNS 地址集合
     * @return 已持久化的允许或拒绝追踪
     */
    public PermissionDecisionTrace evaluate(
            String grantId,
            long frozenRevision,
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> dnsAddresses) {
        String checkedId = identifier(grantId);
        if (frozenRevision < 1) {
            throw new IllegalArgumentException("frozenRevision must be positive");
        }
        WorkspaceId checkedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        PrivateNetworkPurpose checkedPurpose = Objects.requireNonNull(purpose, "purpose");
        URI checkedOrigin = PrivateNetworkGrant.normalizeOrigin(origin);
        Set<String> checkedAddresses = PrivateNetworkAddressPolicy.requireGrantable(dnsAddresses);
        return execute(connection -> {
            Optional<PrivateNetworkGrant> current = grants.latest(
                            connection, SecurityGrantRepository.GrantTable.PRIVATE_NETWORK, checkedId, false)
                    .map(this::decodePrivate);
            List<PermissionDecisionTrace.Step> steps = decisionSteps(
                    current, frozenRevision, checkedWorkspace, checkedPurpose, checkedOrigin, checkedAddresses);
            PermissionDecisionTrace trace =
                    trace(checkedWorkspace, checkedId, "private-network/connect", checkedOrigin.toString(), steps);
            grants.insertTrace(connection, trace, json.encode(trace));
            return trace;
        });
    }

    /**
     * 与 {@link #evaluate} 相同，但拒绝时抛出安全异常供 Broker fail closed。
     *
     * @param grantId 冻结授权标识
     * @param frozenRevision 冻结 revision
     * @param workspaceId 当前 Workspace
     * @param purpose 当前用途
     * @param origin 当前 Origin
     * @param dnsAddresses 当前 DNS 地址集合
     * @return 允许追踪
     */
    public PermissionDecisionTrace requireAuthorized(
            String grantId,
            long frozenRevision,
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> dnsAddresses) {
        PermissionDecisionTrace trace = evaluate(grantId, frozenRevision, workspaceId, purpose, origin, dnsAddresses);
        if (!trace.allowed()) {
            throw new SecurityException(trace.denialReason().orElse("私网授权拒绝连接"));
        }
        return trace;
    }

    private List<PermissionDecisionTrace.Step> decisionSteps(
            Optional<PrivateNetworkGrant> current,
            long frozenRevision,
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> addresses) {
        if (current.isEmpty()) {
            return List.of(step("grant-exists", 0, false, "授权不存在"));
        }
        PrivateNetworkGrant grant = current.orElseThrow();
        List<PermissionDecisionTrace.Step> steps = new ArrayList<>();
        steps.add(step("system-network-ceiling", 0, true, "地址未命中永久拒绝范围"));
        steps.add(step(
                "grant-revision",
                grant.revision(),
                grant.revision() == frozenRevision && grant.state() == SecurityGrantState.ACTIVE,
                "冻结版本必须仍是最新活动版本"));
        steps.add(step("workspace", grant.revision(), grant.workspaceId().equals(workspaceId), "Workspace 必须精确匹配"));
        steps.add(step("purpose", grant.revision(), grant.purpose() == purpose, "授权用途必须精确匹配"));
        steps.add(step("origin", grant.revision(), grant.origin().equals(origin), "HTTPS Origin 必须精确匹配"));
        steps.add(step("dns-addresses", grant.revision(), grant.dnsAddresses().equals(addresses), "DNS 地址集合必须精确匹配"));
        steps.add(step("expiration", grant.revision(), now().isBefore(grant.expiresAt()), "授权必须仍在有效期内"));
        return List.copyOf(steps);
    }

    private PermissionDecisionTrace trace(
            WorkspaceId workspaceId,
            String grantId,
            String operation,
            String resource,
            List<PermissionDecisionTrace.Step> steps) {
        Optional<String> denial = steps.stream()
                .filter(step -> !step.allowed())
                .map(PermissionDecisionTrace.Step::explanation)
                .findFirst();
        return new PermissionDecisionTrace(
                UUID.randomUUID().toString(),
                workspaceId,
                SecurityGrantKind.PRIVATE_NETWORK,
                grantId,
                operation,
                resource,
                denial.isEmpty(),
                steps,
                denial,
                now());
    }

    private PrivateNetworkGrantPreview requireFreshPreview(PrivateNetworkGrantPreview preview) {
        PrivateNetworkGrantPreview checked = Objects.requireNonNull(preview, "preview");
        Set<String> addresses = PrivateNetworkAddressPolicy.requireGrantable(checked.dnsAddresses());
        PreviewContent content = new PreviewContent(
                checked.workspaceId(), checked.purpose(), checked.origin(), addresses, checked.expiresAt());
        if (!json.encode(content).sha256().equals(checked.confirmationDigest())) {
            throw PersistenceException.invalidRequest("私网授权确认摘要不匹配");
        }
        Duration remaining = Duration.between(now(), checked.expiresAt());
        requireValidity(remaining);
        return new PrivateNetworkGrantPreview(
                checked.workspaceId(),
                checked.purpose(),
                checked.origin(),
                addresses,
                checked.expiresAt(),
                checked.confirmationDigest());
    }

    private SecurityGrantRepository.StoredGrant stored(PrivateNetworkGrant grant) {
        return new SecurityGrantRepository.StoredGrant(
                grant.id(),
                grant.revision(),
                grant.state().name(),
                grant.workspaceId(),
                json.encode(grant),
                grant.createdAt(),
                grant.updatedAt());
    }

    private PrivateNetworkGrant decodePrivate(SecurityGrantRepository.StoredGrant stored) {
        PrivateNetworkGrant grant = json.decode(stored.payload(), PrivateNetworkGrant.class);
        if (!stored.id().equals(grant.id())
                || stored.revision() != grant.revision()
                || !stored.state().equals(grant.state().name())
                || !stored.workspaceId().equals(grant.workspaceId())
                || !stored.createdAt().equals(grant.createdAt())
                || !stored.updatedAt().equals(grant.updatedAt())) {
            throw new PersistenceException("私网授权行身份与 payload 不一致");
        }
        return grant;
    }

    private void requireWorkspace(java.sql.Connection connection, WorkspaceId workspaceId)
            throws java.sql.SQLException {
        if (!grants.workspaceExists(connection, workspaceId)) {
            throw PersistenceException.invalidRequest("Workspace 不存在");
        }
    }

    private static Duration requireValidity(Duration validity) {
        Duration checked = Objects.requireNonNull(validity, "validity");
        if (checked.isZero() || checked.isNegative() || checked.compareTo(MAXIMUM_VALIDITY) > 0) {
            throw new IllegalArgumentException("private network grant validity must be within 24 hours");
        }
        return checked;
    }

    private static CommandIdentity requireCreateIdentity(CommandIdentity identity) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (checked.expectedRevision() != 0) {
            throw PersistenceException.revisionConflict("创建私网授权的 expected revision 必须为 0");
        }
        return checked;
    }

    private static void requireRevocable(SecurityGrantState state, long revision, long expectedRevision) {
        if (revision != expectedRevision) {
            throw PersistenceException.revisionConflict("私网授权 revision 已改变");
        }
        if (state == SecurityGrantState.REVOKED) {
            throw PersistenceException.invalidRequest("私网授权已经撤销");
        }
    }

    private static PermissionDecisionTrace.Step step(String source, long revision, boolean allowed, String detail) {
        return new PermissionDecisionTrace.Step(source, revision, allowed, detail);
    }

    private static String identifier(String value) {
        String checked = Objects.requireNonNull(value, "grantId").strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("grantId contains unsupported characters");
        }
        return checked;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("私网授权事务失败", failure);
        }
    }

    private record PreviewContent(
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> dnsAddresses,
            Instant expiresAt) {}
}
