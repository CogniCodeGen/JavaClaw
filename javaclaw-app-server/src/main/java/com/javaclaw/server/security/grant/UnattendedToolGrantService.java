package com.javaclaw.server.security.grant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.api.UnattendedInvocationOutcome;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.UnattendedToolInvocation;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.IdempotentCommandStore;
import com.javaclaw.server.persistence.PersistenceException;

/** Schedule 专用、固定参数且严格限额的无人值守工具授权服务。 */
public final class UnattendedToolGrantService {
    /** 无人值守授权的绝对期限上限。 */
    public static final Duration MAXIMUM_VALIDITY = Duration.ofDays(30);

    private final H2Transactions transactions;
    private final SecurityGrantRepository grants = new SecurityGrantRepository();
    private final IdempotentCommandStore commands = new IdempotentCommandStore();
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建无人值守授权服务。
     *
     * @param database data-v6 数据库
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public UnattendedToolGrantService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 创建 Schedule 专用授权；expected revision 必须为 0。
     *
     * @param identity 幂等命令身份
     * @param draft 用户已确认配置
     * @return 首个授权版本
     */
    public UnattendedToolGrant create(CommandIdentity identity, UnattendedToolGrantDraft draft) {
        CommandIdentity checkedIdentity = requireCreateIdentity(identity);
        UnattendedToolGrantDraft checkedDraft = UnattendedGrantSafety.requireSafe(draft, json);
        synchronized (GrantCommandLocks.forKey(checkedIdentity.idempotencyKey())) {
            return execute(connection -> {
                Optional<CanonicalPayload> recovered = commands.recover(connection, checkedIdentity);
                if (recovered.isPresent()) {
                    return json.decode(recovered.orElseThrow(), UnattendedToolGrant.class);
                }
                requireWorkspace(connection, checkedDraft.workspaceId());
                Instant now = now();
                UnattendedToolGrant grant = new UnattendedToolGrant(
                        UUID.randomUUID().toString(),
                        1,
                        SecurityGrantState.ACTIVE,
                        checkedDraft.workspaceId(),
                        checkedDraft.scheduleId(),
                        checkedDraft.scheduleRevision(),
                        checkedDraft.tool(),
                        checkedDraft.catalogRevision(),
                        checkedDraft.schemaHash(),
                        checkedDraft.fixedArguments(),
                        checkedDraft.variableStringFields(),
                        checkedDraft.maximumUses(),
                        now.plus(checkedDraft.validity()),
                        now,
                        now);
                grants.insertGrant(connection, SecurityGrantRepository.GrantTable.UNATTENDED_TOOL, stored(grant));
                commands.record(connection, checkedIdentity, json.encode(grant), now);
                return grant;
            });
        }
    }

    /**
     * 写入不可逆撤销 tombstone；后续工具调用立即读取该版本。
     *
     * @param identity expected revision 必须匹配当前版本
     * @param grantId 授权标识
     * @return 撤销版本
     */
    public UnattendedToolGrant revoke(CommandIdentity identity, String grantId) {
        CommandIdentity checkedIdentity = Objects.requireNonNull(identity, "identity");
        String checkedId = identifier(grantId);
        synchronized (GrantCommandLocks.forKey(checkedIdentity.idempotencyKey())) {
            return execute(connection -> {
                Optional<CanonicalPayload> recovered = commands.recover(connection, checkedIdentity);
                if (recovered.isPresent()) {
                    return json.decode(recovered.orElseThrow(), UnattendedToolGrant.class);
                }
                UnattendedToolGrant observed = grants.latest(
                                connection, SecurityGrantRepository.GrantTable.UNATTENDED_TOOL, checkedId, false)
                        .map(this::decodeUnattended)
                        .orElseThrow(() -> PersistenceException.invalidRequest("无人值守授权不存在"));
                requireWorkspace(connection, observed.workspaceId());
                UnattendedToolGrant current = grants.latest(
                                connection, SecurityGrantRepository.GrantTable.UNATTENDED_TOOL, checkedId, true)
                        .map(this::decodeUnattended)
                        .orElseThrow(() -> PersistenceException.invalidRequest("无人值守授权不存在"));
                requireRevocable(current.state(), current.revision(), checkedIdentity.expectedRevision());
                Instant now = now();
                UnattendedToolGrant revoked = tombstone(current, now);
                grants.insertGrant(connection, SecurityGrantRepository.GrantTable.UNATTENDED_TOOL, stored(revoked));
                commands.record(connection, checkedIdentity, json.encode(revoked), now);
                return revoked;
            });
        }
    }

    /**
     * 列出 Workspace 中每个授权的最新版本与使用余额。
     *
     * @param workspaceId Workspace
     * @return 按授权标识排序的投影
     */
    public List<UnattendedToolGrantStatus> listLatest(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return execute(connection -> {
            List<UnattendedToolGrantStatus> statuses = new ArrayList<>();
            for (SecurityGrantRepository.StoredGrant stored :
                    grants.listLatest(connection, SecurityGrantRepository.GrantTable.UNATTENDED_TOOL, workspaceId)) {
                UnattendedToolGrant grant = decodeUnattended(stored);
                int consumed = grants.countUses(connection, grant.id());
                statuses.add(new UnattendedToolGrantStatus(grant, consumed, grant.maximumUses() - consumed));
            }
            return List.copyOf(statuses);
        });
    }

    /**
     * 读取授权全部不可变版本。
     *
     * @param grantId 授权标识
     * @return revision 升序历史
     */
    public List<UnattendedToolGrant> history(String grantId) {
        String checkedId = identifier(grantId);
        return execute(connection ->
                grants.history(connection, SecurityGrantRepository.GrantTable.UNATTENDED_TOOL, checkedId).stream()
                        .map(this::decodeUnattended)
                        .toList());
    }

    /**
     * 在工具执行前原子判断授权并消费一次额度。
     *
     * <p>同一 invocation 无论之前处于运行、成功、失败或未知结果，都不会再次获准。授权拒绝不会消费额度，但会持久化脱敏决策追踪。
     *
     * @param invocation Schedule、工具、Schema 与参数的完整冻结身份
     * @return 已持久化的允许或拒绝追踪
     */
    public PermissionDecisionTrace authorizeAndConsume(UnattendedToolInvocation invocation) {
        UnattendedToolInvocation checked = Objects.requireNonNull(invocation, "invocation");
        return execute(connection -> {
            requireWorkspace(connection, checked.workspaceId());
            return authorizeAndConsume(connection, checked);
        });
    }

    /**
     * 为一个已经持久化来源的 Schedule Tool 调用解析唯一可用授权并原子预留额度。
     *
     * <p>授权选择覆盖 Schedule revision、工具身份、目录 revision、输入 Schema 与参数模板。没有唯一匹配时失败关闭，普通 Turn 不能进入该入口。
     *
     * @param scope Turn 创建事务中冻结的 Schedule 来源
     * @param descriptor 冻结工具描述
     * @param catalogRevision 冻结目录 revision
     * @param arguments 本次规范参数
     * @param invocationId 稳定调用标识
     * @return 已消费一次额度的授权预留
     */
    public UnattendedToolReservation reserve(
            UnattendedExecutionScope scope,
            ToolDescriptor descriptor,
            long catalogRevision,
            CanonicalPayload arguments,
            String invocationId) {
        UnattendedExecutionScope checkedScope = Objects.requireNonNull(scope, "scope");
        ToolDescriptor checkedDescriptor = Objects.requireNonNull(descriptor, "descriptor");
        CanonicalPayload checkedArguments = Objects.requireNonNull(arguments, "arguments");
        String checkedInvocation = identifier(invocationId);
        return execute(connection -> {
            requireWorkspace(connection, checkedScope.workspaceId());
            List<UnattendedToolGrant> candidates = new ArrayList<>();
            for (SecurityGrantRepository.StoredGrant stored : grants.listLatest(
                    connection, SecurityGrantRepository.GrantTable.UNATTENDED_TOOL, checkedScope.workspaceId())) {
                UnattendedToolGrant grant = decodeUnattended(stored);
                if (usableBinding(
                        connection, grant, checkedScope, checkedDescriptor, catalogRevision, checkedArguments)) {
                    candidates.add(grant);
                }
            }
            if (candidates.size() != 1) {
                throw new SecurityException("Schedule 工具调用没有唯一可用的无人值守授权");
            }
            UnattendedToolGrant grant = candidates.getFirst();
            UnattendedToolInvocation invocation = new UnattendedToolInvocation(
                    checkedScope.workspaceId(),
                    grant.id(),
                    grant.revision(),
                    checkedScope.scheduleId(),
                    checkedScope.scheduleRevision(),
                    checkedDescriptor.identity(),
                    catalogRevision,
                    checkedDescriptor.inputSchema().sha256(),
                    checkedArguments,
                    checkedInvocation);
            PermissionDecisionTrace trace = authorizeAndConsume(connection, invocation);
            if (!trace.allowed()) {
                throw new SecurityException(trace.denialReason().orElse("无人值守工具授权拒绝调用"));
            }
            return new UnattendedToolReservation(grant.id(), grant.revision(), checkedInvocation, trace);
        });
    }

    /**
     * 判断并消费额度，拒绝时抛出安全异常供工具治理层 fail closed。
     *
     * @param invocation 完整冻结调用
     * @return 允许追踪
     */
    public PermissionDecisionTrace requireAndConsume(UnattendedToolInvocation invocation) {
        PermissionDecisionTrace trace = authorizeAndConsume(invocation);
        if (!trace.allowed()) {
            throw new SecurityException(trace.denialReason().orElse("无人值守工具授权拒绝调用"));
        }
        return trace;
    }

    /**
     * 记录已经预留额度的调用终态。
     *
     * @param grantId 授权标识
     * @param invocationId 调用标识
     * @param outcome 明确结果或 UNKNOWN_OUTCOME
     */
    public void recordOutcome(String grantId, String invocationId, UnattendedInvocationOutcome outcome) {
        String checkedGrant = identifier(grantId);
        String checkedInvocation = identifier(invocationId);
        UnattendedInvocationOutcome checkedOutcome = Objects.requireNonNull(outcome, "outcome");
        execute(connection -> {
            SecurityGrantRepository.StoredUse current = grants.findUse(connection, checkedGrant, checkedInvocation)
                    .orElseThrow(() -> PersistenceException.invalidRequest("无人值守调用预留不存在"));
            if (current.outcome().equals(checkedOutcome.name())) {
                return null;
            }
            if (!"RESERVED".equals(current.outcome())) {
                throw PersistenceException.invalidRequest("无人值守调用已经记录不同终态");
            }
            if (grants.updateOutcome(connection, checkedGrant, checkedInvocation, checkedOutcome.name(), now()) != 1) {
                throw new PersistenceException("无人值守调用终态提交失败");
            }
            return null;
        });
    }

    /**
     * 提交预留的终态。
     *
     * @param reservation 已消费额度的预留
     * @param outcome 明确结果或 UNKNOWN_OUTCOME
     */
    public void recordOutcome(UnattendedToolReservation reservation, UnattendedInvocationOutcome outcome) {
        UnattendedToolReservation checked = Objects.requireNonNull(reservation, "reservation");
        recordOutcome(checked.grantId(), checked.invocationId(), outcome);
    }

    /**
     * 启动恢复时把所有未完成预留标记为 UNKNOWN_OUTCOME。
     *
     * <p>这些记录已经消费额度，后续同一 invocation 会被拒绝，避免未知副作用被自动重放。
     *
     * @return 转换的调用数
     */
    public int recoverUnknownOutcomes() {
        return execute(connection -> grants.markReservedUnknown(connection, now()));
    }

    private List<PermissionDecisionTrace.Step> decisionSteps(
            Optional<UnattendedToolGrant> current,
            UnattendedToolInvocation invocation,
            Optional<SecurityGrantRepository.StoredUse> prior,
            int consumed) {
        if (current.isEmpty()) {
            return List.of(step("grant-exists", 0, false, "授权不存在"));
        }
        UnattendedToolGrant grant = current.orElseThrow();
        List<PermissionDecisionTrace.Step> steps = new ArrayList<>();
        steps.add(step(
                "grant-revision",
                grant.revision(),
                grant.revision() == invocation.grantRevision() && grant.state() == SecurityGrantState.ACTIVE,
                "冻结版本必须仍是最新活动版本"));
        steps.add(step(
                "workspace",
                grant.revision(),
                grant.workspaceId().equals(invocation.workspaceId()),
                "Workspace 必须精确匹配"));
        steps.add(
                step("schedule", grant.revision(), scheduleMatches(grant, invocation), "Schedule 定义与 revision 必须精确匹配"));
        steps.add(step("tool", grant.revision(), grant.tool().equals(invocation.tool()), "工具来源、名称与 revision 必须精确匹配"));
        steps.add(step(
                "catalog",
                grant.revision(),
                grant.catalogRevision() == invocation.catalogRevision(),
                "工具目录 revision 必须精确匹配"));
        steps.add(step(
                "schema",
                grant.revision(),
                grant.schemaHash().equals(invocation.schemaHash()),
                "输入 Schema hash 必须精确匹配"));
        steps.add(step(
                "arguments",
                grant.revision(),
                json.matchesExceptTopLevelStrings(
                        grant.fixedArguments(), invocation.arguments(), grant.variableStringFields()),
                "参数只能改变已批准的普通字符串字段"));
        steps.add(step("expiration", grant.revision(), now().isBefore(grant.expiresAt()), "授权必须仍在有效期内"));
        steps.add(step("quota", grant.revision(), consumed < grant.maximumUses(), "授权必须仍有剩余额度"));
        steps.add(step("invocation", grant.revision(), prior.isEmpty(), priorExplanation(prior)));
        return List.copyOf(steps);
    }

    private PermissionDecisionTrace authorizeAndConsume(
            java.sql.Connection connection, UnattendedToolInvocation invocation) throws java.sql.SQLException {
        Optional<UnattendedToolGrant> current = grants.latest(
                        connection, SecurityGrantRepository.GrantTable.UNATTENDED_TOOL, invocation.grantId(), true)
                .map(this::decodeUnattended);
        Optional<SecurityGrantRepository.StoredUse> prior =
                grants.findUse(connection, invocation.grantId(), invocation.invocationId());
        int consumed = grants.countUses(connection, invocation.grantId());
        List<PermissionDecisionTrace.Step> steps = decisionSteps(current, invocation, prior, consumed);
        PermissionDecisionTrace trace = trace(invocation, steps);
        if (trace.allowed()) {
            grants.insertUse(
                    connection, invocation.grantId(), invocation.invocationId(), invocation.grantRevision(), now());
        }
        grants.insertTrace(connection, trace, json.encode(trace));
        return trace;
    }

    private boolean usableBinding(
            java.sql.Connection connection,
            UnattendedToolGrant grant,
            UnattendedExecutionScope scope,
            ToolDescriptor descriptor,
            long catalogRevision,
            CanonicalPayload arguments)
            throws java.sql.SQLException {
        return grant.state() == SecurityGrantState.ACTIVE
                && now().isBefore(grant.expiresAt())
                && grants.countUses(connection, grant.id()) < grant.maximumUses()
                && grant.workspaceId().equals(scope.workspaceId())
                && grant.scheduleId().equals(scope.scheduleId())
                && grant.scheduleRevision() == scope.scheduleRevision()
                && grant.tool().equals(descriptor.identity())
                && grant.catalogRevision() == catalogRevision
                && grant.schemaHash().equals(descriptor.inputSchema().sha256())
                && json.matchesExceptTopLevelStrings(grant.fixedArguments(), arguments, grant.variableStringFields());
    }

    private PermissionDecisionTrace trace(
            UnattendedToolInvocation invocation, List<PermissionDecisionTrace.Step> steps) {
        Optional<String> denial = steps.stream()
                .filter(step -> !step.allowed())
                .map(PermissionDecisionTrace.Step::explanation)
                .findFirst();
        String resource = invocation.tool().producerId()
                + ':'
                + invocation.tool().name()
                + '@'
                + invocation.tool().revision();
        return new PermissionDecisionTrace(
                UUID.randomUUID().toString(),
                invocation.workspaceId(),
                SecurityGrantKind.UNATTENDED_TOOL,
                invocation.grantId(),
                "schedule/tool-call",
                resource,
                denial.isEmpty(),
                steps,
                denial,
                now());
    }

    private UnattendedToolGrant tombstone(UnattendedToolGrant current, Instant now) {
        return new UnattendedToolGrant(
                current.id(),
                Math.addExact(current.revision(), 1),
                SecurityGrantState.REVOKED,
                current.workspaceId(),
                current.scheduleId(),
                current.scheduleRevision(),
                current.tool(),
                current.catalogRevision(),
                current.schemaHash(),
                current.fixedArguments(),
                current.variableStringFields(),
                current.maximumUses(),
                current.expiresAt(),
                current.createdAt(),
                now);
    }

    private SecurityGrantRepository.StoredGrant stored(UnattendedToolGrant grant) {
        return new SecurityGrantRepository.StoredGrant(
                grant.id(),
                grant.revision(),
                grant.state().name(),
                grant.workspaceId(),
                json.encode(grant),
                grant.createdAt(),
                grant.updatedAt());
    }

    private UnattendedToolGrant decodeUnattended(SecurityGrantRepository.StoredGrant stored) {
        UnattendedToolGrant grant = json.decode(stored.payload(), UnattendedToolGrant.class);
        if (!stored.id().equals(grant.id())
                || stored.revision() != grant.revision()
                || !stored.state().equals(grant.state().name())
                || !stored.workspaceId().equals(grant.workspaceId())
                || !stored.createdAt().equals(grant.createdAt())
                || !stored.updatedAt().equals(grant.updatedAt())) {
            throw new PersistenceException("无人值守授权行身份与 payload 不一致");
        }
        return grant;
    }

    private void requireWorkspace(java.sql.Connection connection, WorkspaceId workspaceId)
            throws java.sql.SQLException {
        if (!grants.lockWorkspace(connection, workspaceId)) {
            throw PersistenceException.invalidRequest("Workspace 不存在");
        }
    }

    private static boolean scheduleMatches(UnattendedToolGrant grant, UnattendedToolInvocation invocation) {
        return grant.scheduleId().equals(invocation.scheduleId())
                && grant.scheduleRevision() == invocation.scheduleRevision();
    }

    private static String priorExplanation(Optional<SecurityGrantRepository.StoredUse> prior) {
        if (prior.isEmpty()) {
            return "invocation 尚未消费额度";
        }
        return "UNKNOWN_OUTCOME".equals(prior.orElseThrow().outcome())
                ? "UNKNOWN_OUTCOME 已消费额度，禁止自动重试"
                : "同一 invocation 已经消费额度，禁止自动重试";
    }

    private static CommandIdentity requireCreateIdentity(CommandIdentity identity) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (checked.expectedRevision() != 0) {
            throw PersistenceException.revisionConflict("创建无人值守授权的 expected revision 必须为 0");
        }
        return checked;
    }

    private static void requireRevocable(SecurityGrantState state, long revision, long expectedRevision) {
        if (revision != expectedRevision) {
            throw PersistenceException.revisionConflict("无人值守授权 revision 已改变");
        }
        if (state == SecurityGrantState.REVOKED) {
            throw PersistenceException.invalidRequest("无人值守授权已经撤销");
        }
    }

    private static PermissionDecisionTrace.Step step(String source, long revision, boolean allowed, String detail) {
        return new PermissionDecisionTrace.Step(source, revision, allowed, detail);
    }

    private static String identifier(String value) {
        String checked = Objects.requireNonNull(value, "identifier").strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("identifier contains unsupported characters");
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
            throw new PersistenceException("无人值守授权事务失败", failure);
        }
    }
}
