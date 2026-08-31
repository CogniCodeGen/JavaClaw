package com.javaclaw.agent.automation;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.agent.runtime.ThreadUseCases;
import com.javaclaw.agent.runtime.TurnUseCases;
import com.javaclaw.agent.runtime.WorkspaceUseCases;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.core.api.Workspace;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.sandbox.api.SandboxMode;

/** Loop, Workflow, SDD and Schedule orchestration over the one Thread/Turn runtime. */
public final class AutomationService {
    private final AutomationRepository repository;
    private final WorkspaceUseCases workspaces;
    private final ThreadUseCases threads;
    private final TurnUseCases turns;
    private final AutomationTurnResolver resolver;
    private final Object[] launchGates = java.util.stream.IntStream.range(0, 64)
            .mapToObj(ignored -> new Object())
            .toArray();

    /** 绑定自动化持久仓库与 Workspace/Thread/Turn 窄接口；不创建第二套 Agent Runtime。 */
    public AutomationService(
            AutomationRepository repository,
            WorkspaceUseCases workspaces,
            ThreadUseCases threads,
            TurnUseCases turns,
            AutomationTurnResolver resolver) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** 列出已保存的自动化定义及其最近执行状态，不触发执行。 */
    public List<AutomationRepository.AutomationDefinition> listAutomations() {
        return repository.listAutomations();
    }

    /** 读取自动化定义；不存在时抛出 NoSuchElementException。 */
    public AutomationRepository.AutomationDefinition readAutomation(String id) {
        return repository
                .findAutomation(id)
                .orElseThrow(() -> new NoSuchElementException("automation not found: " + id));
    }

    /** 创建或按预期修订号更新自动化定义；幂等键防止重复写入，不启动 Turn。 */
    public AutomationRepository.AutomationDefinition putAutomation(
            AutomationRepository.AutomationDraft draft, long revision, String key) {
        com.javaclaw.agent.tool.AutomationPlans.parse(draft.kind(), draft.definitionJson());
        synchronized (gate("automation", draft.id())) {
            return repository.putAutomation(draft, revision, key);
        }
    }

    /** 按版本删除自动化定义并返回是否删除成功；不隐式删除绑定 Thread 的历史。 */
    public boolean deleteAutomation(String id, long revision, String key) {
        synchronized (gate("automation", id)) {
            return repository.deleteAutomation(id, revision, key);
        }
    }

    /** 解析对应 Profile，在已有或新建的稳定 Thread 启动 Turn 并记录绑定；返回启动快照，活动冲突由 Runtime 拒绝。 */
    public AgentTurn startAutomation(String id, String idempotencyKey) {
        return launchAutomation(id, idempotencyKey, false);
    }

    /** 在同一逻辑执行中恢复新 Turn；不允许重置预算、丢弃未知副作用或沿用变更后的审批。 */
    public AgentTurn resumeAutomation(String id, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("resume requires an idempotency key");
        }
        return launchAutomation(id, idempotencyKey, true);
    }

    /** 返回执行历史供 SDK 的检查点、规格和结果页面使用；结果按持久顺序排列。 */
    public List<com.javaclaw.core.api.StoredItem> executionItems(String id) {
        var definition = readAutomation(id);
        return definition.threadId() == null
                ? List.of()
                : requireThread(definition.threadId()).items();
    }

    private AgentTurn launchAutomation(String id, String idempotencyKey, boolean resume) {
        synchronized (gate("automation", id)) {
            var definition = readAutomation(id);
            Workspace workspace = workspace(definition.workspaceId());
            if (definition.threadId() != null && idempotencyKey != null) {
                var replay = threads.readTurnByIdempotencyKey(new ThreadId(definition.threadId()), idempotencyKey);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            var resolved = resolver.resolve(
                    definition.profileId(), workspace, definition.kind().profileKind());
            AgentThread thread;
            if (definition.threadId() == null) {
                thread = threads.startThread(workspace.id(), definition.name(), "automation-thread:" + definition.id());
                // 先持久化稳定 Thread；崩溃在启动前或启动后，重试都不会创建第二条执行链。
                definition = repository.bindAutomationRun(
                        definition.id(), definition.revision(), thread.id().value(), null, "READY");
            } else {
                thread = requireThread(definition.threadId()).thread();
            }
            AgentTurn turn = turns.startTurn(new TurnStartCommand(
                    thread.id(),
                    List.of(new TurnInput.Text(definition.prompt())),
                    executionConfig(definition, resolved.turnConfig(), thread, resume),
                    idempotencyKey));
            repository.bindAutomationRun(
                    definition.id(),
                    definition.revision(),
                    thread.id().value(),
                    turn.id().value(),
                    "RUNNING");
            return turn;
        }
    }

    private com.javaclaw.core.api.TurnConfig executionConfig(
            AutomationRepository.AutomationDefinition definition,
            com.javaclaw.core.api.TurnConfig config,
            AgentThread thread,
            boolean resume) {
        var plan = com.javaclaw.agent.tool.AutomationPlans.parse(definition.kind(), definition.definitionJson());
        var attributes = new java.util.LinkedHashMap<>(config.attributes());
        String hash = com.javaclaw.agent.prompt.PromptHashes.sha256(
                definition.kind() + "\n" + definition.prompt() + "\n" + definition.definitionJson());
        attributes.put("automationId", definition.id());
        attributes.put("automationKind", definition.kind().name());
        attributes.put("automationDefinition", definition.definitionJson());
        attributes.put("automationDefinitionHash", hash);
        int calls = Math.min(plan.limits().modelCalls(), finite(config, "maxModelCalls", 100));
        long tokens = Math.min(plan.limits().tokens(), finite(config, "maxTokens", 200_000));
        int seconds = Math.min(plan.limits().seconds(), finite(config, "maxDurationSeconds", 3_600));
        var effectivePolicy = config.sandboxPolicy();
        Set<String> effectiveTools = config.enabledTools();
        int usedCalls = 0;
        long usedTokens = 0;
        long elapsed = 0;
        String execution = "execution_" + UUID.randomUUID().toString().replace("-", "");
        if (resume) {
            var snapshot = requireThread(thread.id().value());
            var checkpoint = snapshot.items().stream()
                    .map(com.javaclaw.core.api.StoredItem::item)
                    .filter(com.javaclaw.core.api.ThreadItem.Checkpoint.class::isInstance)
                    .map(com.javaclaw.core.api.ThreadItem.Checkpoint.class::cast)
                    .reduce((left, right) -> right)
                    .orElseThrow(() -> new IllegalStateException("no durable checkpoint to resume"));
            if (!hash.equals(checkpoint.definitionHash()) || "COMPLETED".equals(checkpoint.status())) {
                throw new IllegalStateException(
                        "definition changed or execution is already complete; start a new execution explicitly");
            }
            execution = checkpoint.executionId();
            usedCalls = checkpoint.usedModelCalls();
            usedTokens = checkpoint.usedTokens();
            elapsed = checkpoint.elapsedMillis();
            // 最新一次模型请求可能在检查点之后崩溃；采用请求前预留的保守预算，不能靠重启增加额度。
            for (var old : snapshot.turns()) {
                if (!execution.equals(old.config().attributes().get("automationExecutionId"))) {
                    continue;
                }
                effectivePolicy = effectivePolicy.intersect(old.config().sandboxPolicy());
                if (!old.config().enabledTools().isEmpty()) {
                    if (effectiveTools.isEmpty()) {
                        effectiveTools = old.config().enabledTools();
                    } else {
                        var common = new java.util.HashSet<>(effectiveTools);
                        common.retainAll(old.config().enabledTools());
                        if (common.isEmpty()) {
                            throw new IllegalStateException("resume tool permissions no longer overlap");
                        }
                        effectiveTools = Set.copyOf(common);
                    }
                }
                // 恢复不能借 Profile 调高上限获得新预算；新配置只能进一步收窄原执行的冻结上限。
                calls = Math.min(
                        calls,
                        Integer.parseInt(old.config()
                                .attributes()
                                .getOrDefault(
                                        "automationCallCeiling",
                                        Integer.toString(finite(old.config(), "maxModelCalls", 100)
                                                + Integer.parseInt(old.config()
                                                        .attributes()
                                                        .getOrDefault("automationUsedCalls", "0"))))));
                tokens = Math.min(
                        tokens,
                        Long.parseLong(old.config()
                                .attributes()
                                .getOrDefault(
                                        "automationTokenCeiling",
                                        Long.toString(finite(old.config(), "maxTokens", 200_000)
                                                + Long.parseLong(old.config()
                                                        .attributes()
                                                        .getOrDefault("automationUsedTokens", "0"))))));
                seconds = Math.min(
                        seconds,
                        Integer.parseInt(old.config()
                                .attributes()
                                .getOrDefault(
                                        "automationTimeCeiling",
                                        Long.toString(finite(old.config(), "maxDurationSeconds", 3_600)
                                                + Long.parseLong(old.config()
                                                                .attributes()
                                                                .getOrDefault("automationElapsedMillis", "0"))
                                                        / 1_000))));
                var budget = snapshot.items().stream()
                        .filter(item -> item.turnId().equals(old.id()))
                        .map(com.javaclaw.core.api.StoredItem::item)
                        .filter(com.javaclaw.core.api.ThreadItem.DynamicToolCall.class::isInstance)
                        .map(com.javaclaw.core.api.ThreadItem.DynamicToolCall.class::cast)
                        .filter(item -> "execution_budget".equals(item.tool()))
                        .reduce((left, right) -> right);
                if (budget.isPresent()) {
                    usedCalls = Math.max(
                            usedCalls,
                            Integer.parseInt(old.config().attributes().getOrDefault("automationUsedCalls", "0"))
                                    + Integer.parseInt(budget.get().result().get("modelCalls")));
                    usedTokens = Math.max(
                            usedTokens,
                            Long.parseLong(old.config().attributes().getOrDefault("automationUsedTokens", "0"))
                                    + Long.parseLong(budget.get().result().get("tokens")));
                }
                long oldElapsed = java.time.Duration.between(
                                old.startedAt(), Objects.requireNonNullElseGet(old.completedAt(), Instant::now))
                        .toMillis();
                elapsed = Math.max(
                        elapsed,
                        Long.parseLong(old.config().attributes().getOrDefault("automationElapsedMillis", "0"))
                                + oldElapsed);
            }
            attributes.put("automationResume", "true");
        }
        if (calls <= usedCalls || tokens <= usedTokens || seconds * 1_000L <= elapsed) {
            throw new IllegalStateException(
                    "execution budget is exhausted; a new execution requires an explicit decision");
        }
        attributes.put("automationExecutionId", execution);
        attributes.put("automationCallCeiling", Integer.toString(calls));
        attributes.put("automationTokenCeiling", Long.toString(tokens));
        attributes.put("automationTimeCeiling", Integer.toString(seconds));
        attributes.put("automationUsedCalls", Integer.toString(usedCalls));
        attributes.put("automationUsedTokens", Long.toString(usedTokens));
        attributes.put("automationElapsedMillis", Long.toString(elapsed));
        attributes.put("maxModelCalls", Integer.toString(calls - usedCalls));
        attributes.put("maxTokens", Long.toString(tokens - usedTokens));
        attributes.put("maxDurationSeconds", Long.toString(Math.max(1, (seconds * 1_000L - elapsed) / 1_000L)));
        return new com.javaclaw.core.api.TurnConfig(
                config.model(),
                config.provider(),
                config.reasoningEffort(),
                config.workingDirectory(),
                effectivePolicy,
                config.approvalPolicy(),
                effectiveTools,
                attributes);
    }

    private static int finite(com.javaclaw.core.api.TurnConfig config, String name, int fallback) {
        int value = Integer.parseInt(config.attributes().getOrDefault(name, "0"));
        return value == 0 ? fallback : value;
    }

    /** 中断绑定的活动 Turn 并保存 INTERRUPTED 状态；没有可取消执行时返回 false。 */
    public boolean interruptAutomation(String id) {
        var definition = readAutomation(id);
        if (definition.activeTurnId() == null) {
            return false;
        }
        boolean interrupted = turns.interrupt(new TurnId(definition.activeTurnId()));
        if (interrupted) {
            repository.bindAutomationRun(
                    definition.id(), definition.revision(), definition.threadId(), null, "INTERRUPTED");
        }
        return interrupted;
    }

    /** 列出持久 Schedule 定义；Quartz 内存状态不是查询权威。 */
    public List<AutomationRepository.ScheduleDefinition> listSchedules() {
        return repository.listSchedules();
    }

    /** 读取 Schedule 定义；不存在时抛出 NoSuchElementException。 */
    public AutomationRepository.ScheduleDefinition readSchedule(String id) {
        return repository.findSchedule(id).orElseThrow(() -> new NoSuchElementException("schedule not found: " + id));
    }

    /** 校验 cron、时区和无人值守 Profile 后保存 Schedule；拒绝 HOST_FULL_ACCESS，返回新修订。 */
    public AutomationRepository.ScheduleDefinition putSchedule(
            AutomationRepository.ScheduleDraft draft, long revision, String key) {
        Objects.requireNonNull(draft, "draft");
        if (!org.quartz.CronExpression.isValidExpression(draft.cronExpression())) {
            throw new IllegalArgumentException("cronExpression is invalid");
        }
        try {
            java.time.ZoneId.of(draft.zoneId());
        } catch (java.time.DateTimeException failure) {
            throw new IllegalArgumentException("zoneId is invalid", failure);
        }
        validateScheduleProfile(draft.profileId(), draft.workspaceId());
        return repository.putSchedule(draft, revision, key);
    }

    /** 按版本保存 Schedule 的启停状态；幂等重放返回对应结果，不立即触发一次执行。 */
    public AutomationRepository.ScheduleDefinition setScheduleEnabled(
            String id, boolean enabled, long revision, String key) {
        return repository.setScheduleEnabled(id, enabled, revision, key);
    }

    /** 按版本删除 Schedule 定义并返回删除结果；稳定 Thread 的历史不随定义一同删除。 */
    public boolean deleteSchedule(String id, long revision, String key) {
        return repository.deleteSchedule(id, revision, key);
    }

    /** Creates one Turn. Active stable threads implement the fixed SKIP overlap policy. */
    public TriggerResult triggerSchedule(String id, boolean requireEnabled, String idempotencyKey) {
        // Quartz 与用户手动触发共用有界条带锁，保护首次绑定和固定 SKIP 策略，不持有任何模型执行锁。
        synchronized (gate("schedule", id)) {
            return triggerScheduleLocked(id, requireEnabled, idempotencyKey);
        }
    }

    private TriggerResult triggerScheduleLocked(String id, boolean requireEnabled, String idempotencyKey) {
        var schedule = readSchedule(id);
        if (requireEnabled && !schedule.enabled()) {
            return TriggerResult.skipped("DISABLED");
        }
        Workspace workspace = workspace(schedule.workspaceId());
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "schedule_" + schedule.id() + "_" + UUID.randomUUID()
                : idempotencyKey;
        AgentThread thread;
        if (schedule.threadId() == null) {
            thread = threads.startThread(
                    workspace.id(), "Schedule: " + schedule.name(), "schedule-thread:" + schedule.id());
            schedule = repository.bindScheduleThread(
                    schedule.id(), schedule.revision(), thread.id().value());
        } else {
            var replay = threads.readTurnByIdempotencyKey(new ThreadId(schedule.threadId()), key);
            if (replay.isPresent()) {
                return new TriggerResult(false, "STARTED", replay.get());
            }
            var snapshot = requireThread(schedule.threadId());
            if (snapshot.turns().stream().anyMatch(turn -> !turn.status().terminal())) {
                repository.recordScheduleFire(schedule.id(), Instant.now(), null, "SKIPPED_OVERLAP");
                return TriggerResult.skipped("OVERLAP");
            }
            thread = snapshot.thread();
        }
        var resolved = resolver.resolve(schedule.profileId(), workspace, ProfileKind.SCHEDULE);
        if (resolved.turnConfig().sandboxPolicy().mode() == SandboxMode.HOST_FULL_ACCESS) {
            throw new IllegalStateException("unattended schedules can never use HOST_FULL_ACCESS");
        }
        AgentTurn turn = turns.startTurn(new TurnStartCommand(
                thread.id(), List.of(new TurnInput.Text(schedule.prompt())), resolved.turnConfig(), key));
        repository.recordScheduleFire(
                schedule.id(), Instant.now(), null, "STARTED:" + turn.id().value());
        return new TriggerResult(false, "STARTED", turn);
    }

    private Object gate(String kind, String id) {
        return launchGates[Math.floorMod(Objects.hash(kind, id), launchGates.length)];
    }

    private void validateScheduleProfile(String profileId, String workspaceId) {
        var resolved = resolver.resolve(profileId, workspace(workspaceId), ProfileKind.SCHEDULE);
        if (resolved.turnConfig().sandboxPolicy().mode() == SandboxMode.HOST_FULL_ACCESS) {
            throw new IllegalArgumentException("schedule profile cannot use HOST_FULL_ACCESS");
        }
    }

    private Workspace workspace(String id) {
        return workspaces
                .readWorkspace(new WorkspaceId(id))
                .orElseThrow(() -> new NoSuchElementException("workspace not found: " + id));
    }

    private com.javaclaw.core.api.ThreadSnapshot requireThread(String id) {
        return threads.readThread(new ThreadId(id))
                .orElseThrow(() -> new NoSuchElementException("thread not found: " + id));
    }

    /**
     * Schedule 的一次触发结果，明确区分实际创建 Turn 与跳过执行。
     *
     * @param skipped 是否因为禁用或重叠而跳过
     * @param reason STARTED 或跳过原因
     * @param turn 已启动 Turn；跳过时为 null
     */
    public record TriggerResult(boolean skipped, String reason, AgentTurn turn) {
        static TriggerResult skipped(String reason) {
            return new TriggerResult(true, reason, null);
        }
    }
}
