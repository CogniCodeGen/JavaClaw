package com.javaclaw.builtin.contracts;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.WorkspaceId;

/** Schedule Definition、H2 权威 Occurrence 与投递契约。 */
public final class ScheduleContracts {
    private ScheduleContracts() {}

    /** 固定触发策略；客户端不能改变并发语义。 */
    public enum OverlapPolicy {
        /** 上一次仍运行时跳过并记录独立 Occurrence。 */
        SKIP_IF_RUNNING
    }

    /** 固定错过触发策略。 */
    public enum MisfirePolicy {
        /** 服务恢复后不补跑历史触发点。 */
        DO_NOT_CATCH_UP
    }

    /** 时间配置类别。 */
    public enum TimingKind {
        /** Quartz Cron 与 IANA Zone。 */
        CRON,
        /** 固定间隔。 */
        FIXED_INTERVAL
    }

    /** Schedule 目标类别。 */
    public enum TargetKind {
        /** 固定业务 Definition revision。 */
        DEFINITION,
        /** 固定 Agent Turn 模板。 */
        TURN_TEMPLATE,
        /** 扩展显式声明的 SchedulableAction。 */
        ACTION
    }

    /** Occurrence 生命周期。 */
    public enum OccurrenceState {
        /** 已在 H2 创建并等待 Outbox。 */
        PENDING,
        /** 已提交 Extension Job。 */
        DISPATCHED,
        /** 目标 Job 正在运行或等待交互。 */
        RUNNING,
        /** 目标正常完成。 */
        COMPLETED,
        /** 目标失败。 */
        FAILED,
        /** 目标取消。 */
        CANCELLED,
        /** 因已有活动 Occurrence 而跳过。 */
        SKIPPED
    }

    /**
     * Cron 或固定间隔时间配置。
     *
     * @param kind 配置类别
     * @param cronExpression Cron 表达式；仅 CRON 存在
     * @param zoneId IANA Zone ID
     * @param interval 固定间隔；仅 FIXED_INTERVAL 存在
     * @param firstFireAt 固定间隔首次触发时间；Cron 时为空
     */
    public record Timing(
            TimingKind kind,
            Optional<String> cronExpression,
            String zoneId,
            Optional<Duration> interval,
            Optional<Instant> firstFireAt) {
        /** 校验时间字段互斥关系和最小一分钟间隔。 */
        public Timing {
            Objects.requireNonNull(kind, "kind");
            cronExpression = optionalText(cronExpression, "cronExpression");
            zoneId = ContractValidation.text(zoneId, "zoneId");
            ZoneId.of(zoneId);
            interval = Objects.requireNonNull(interval, "interval");
            firstFireAt = Objects.requireNonNull(firstFireAt, "firstFireAt");
            boolean valid =
                    switch (kind) {
                        case CRON -> cronExpression.isPresent() && interval.isEmpty() && firstFireAt.isEmpty();
                        case FIXED_INTERVAL ->
                            cronExpression.isEmpty() && validInterval(interval) && firstFireAt.isPresent();
                    };
            if (!valid) {
                throw new IllegalArgumentException("Schedule timing fields do not match kind");
            }
        }

        /**
         * 创建 Cron 时间配置。
         *
         * @param expression Quartz Cron
         * @param zoneId IANA Zone
         * @return Cron 配置
         */
        public static Timing cron(String expression, String zoneId) {
            return new Timing(TimingKind.CRON, Optional.of(expression), zoneId, Optional.empty(), Optional.empty());
        }

        /**
         * 创建固定间隔配置。
         *
         * @param interval 间隔，至少一分钟
         * @param firstFireAt 首次触发时间
         * @return 固定间隔配置
         */
        public static Timing fixed(Duration interval, Instant firstFireAt) {
            return new Timing(
                    TimingKind.FIXED_INTERVAL,
                    Optional.empty(),
                    "UTC",
                    Optional.of(interval),
                    Optional.of(firstFireAt));
        }

        private static boolean validInterval(Optional<Duration> value) {
            return value.filter(duration -> !duration.isNegative() && !duration.isZero())
                    .filter(duration -> duration.compareTo(Duration.ofMinutes(1)) >= 0)
                    .isPresent();
        }
    }

    /**
     * 固定业务 Definition 目标。
     *
     * @param extensionId Definition 所有者
     * @param definitionId Definition 标识
     * @param definitionRevision 固定 revision
     * @param execution 独立执行配置，在 Occurrence 创建时冻结
     * @param budget Execution 总预算
     */
    public record DefinitionTarget(
            String extensionId,
            String definitionId,
            long definitionRevision,
            ExecutionOverrides execution,
            OrchestrationContracts.ExecutionBudget budget) {
        /** 校验 Definition 目标。 */
        public DefinitionTarget {
            extensionId = ContractValidation.text(extensionId, "extensionId");
            definitionId = ContractValidation.text(definitionId, "definitionId");
            definitionRevision = ContractValidation.revision(definitionRevision);
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(budget, "budget");
        }
    }

    /**
     * 固定 Turn 模板目标。
     *
     * @param execution 独立执行配置，在 Occurrence 创建时冻结
     * @param title Thread 标题
     * @param instruction 冻结指令
     * @param budget Execution 总预算
     */
    public record TurnTemplate(
            ExecutionOverrides execution,
            String title,
            String instruction,
            OrchestrationContracts.ExecutionBudget budget) {
        /** 校验 Turn 模板。 */
        public TurnTemplate {
            Objects.requireNonNull(execution, "execution");
            title = ContractValidation.text(title, "title");
            instruction = ContractValidation.text(instruction, "instruction");
            Objects.requireNonNull(budget, "budget");
        }
    }

    /**
     * 三类目标的互斥封装。
     *
     * @param kind 目标类别
     * @param definition Definition 目标
     * @param turnTemplate Turn 模板
     * @param action SchedulableAction
     */
    public record Target(
            TargetKind kind,
            Optional<DefinitionTarget> definition,
            Optional<TurnTemplate> turnTemplate,
            Optional<ScheduleActionContracts.Target> action) {
        /** 校验目标字段精确匹配类别。 */
        public Target {
            Objects.requireNonNull(kind, "kind");
            definition = Objects.requireNonNull(definition, "definition");
            turnTemplate = Objects.requireNonNull(turnTemplate, "turnTemplate");
            action = Objects.requireNonNull(action, "action");
            boolean valid =
                    switch (kind) {
                        case DEFINITION -> definition.isPresent() && turnTemplate.isEmpty() && action.isEmpty();
                        case TURN_TEMPLATE -> definition.isEmpty() && turnTemplate.isPresent() && action.isEmpty();
                        case ACTION -> definition.isEmpty() && turnTemplate.isEmpty() && action.isPresent();
                    };
            if (!valid) {
                throw new IllegalArgumentException("Schedule target fields do not match kind");
            }
        }

        /**
         * 创建 Definition 目标。
         *
         * @param target Definition 目标
         * @return 互斥目标
         */
        public static Target definition(DefinitionTarget target) {
            return new Target(TargetKind.DEFINITION, Optional.of(target), Optional.empty(), Optional.empty());
        }

        /**
         * 创建 Turn 模板目标。
         *
         * @param target Turn 模板
         * @return 互斥目标
         */
        public static Target turn(TurnTemplate target) {
            return new Target(TargetKind.TURN_TEMPLATE, Optional.empty(), Optional.of(target), Optional.empty());
        }

        /**
         * 创建 Action 目标。
         *
         * @param target Action 目标
         * @return 互斥目标
         */
        public static Target action(ScheduleActionContracts.Target target) {
            return new Target(TargetKind.ACTION, Optional.empty(), Optional.empty(), Optional.of(target));
        }
    }

    /**
     * 用户可编辑 Schedule Definition。
     *
     * @param id Schedule 标识
     * @param revision 乐观锁版本
     * @param name 名称
     * @param enabled 是否启用
     * @param timing 时间配置
     * @param target 固定目标
     * @param overlapPolicy 固定并发策略
     * @param misfirePolicy 固定错过策略
     * @param updatedAt 更新时间
     */
    public record Definition(
            String id,
            long revision,
            String name,
            boolean enabled,
            Timing timing,
            Target target,
            OverlapPolicy overlapPolicy,
            MisfirePolicy misfirePolicy,
            Instant updatedAt)
            implements VersionedExtensionDocument {
        /** 校验 Schedule Definition。 */
        public Definition {
            id = ContractValidation.text(id, "id");
            revision = ContractValidation.revision(revision);
            name = ContractValidation.text(name, "name");
            Objects.requireNonNull(timing, "timing");
            Objects.requireNonNull(target, "target");
            if (overlapPolicy != OverlapPolicy.SKIP_IF_RUNNING || misfirePolicy != MisfirePolicy.DO_NOT_CATCH_UP) {
                throw new IllegalArgumentException("Schedule policies are fixed by platform");
            }
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
        }
    }

    /**
     * Occurrence 稳定身份。
     *
     * @param id Occurrence ID
     * @param scheduleId Schedule ID
     * @param scheduleRevision 触发时冻结的 Schedule revision
     */
    public record OccurrenceIdentity(String id, String scheduleId, long scheduleRevision) {
        /** 校验身份。 */
        public OccurrenceIdentity {
            id = ContractValidation.text(id, "id");
            scheduleId = ContractValidation.text(scheduleId, "scheduleId");
            scheduleRevision = ContractValidation.revision(scheduleRevision);
        }
    }

    /**
     * Occurrence 运行状态。
     *
     * @param state 生命周期状态
     * @param jobId 目标 Extension Job
     * @param reason 跳过或失败的脱敏原因
     */
    public record OccurrenceStatus(OccurrenceState state, Optional<String> jobId, Optional<String> reason) {
        /** 校验状态附属字段。 */
        public OccurrenceStatus {
            Objects.requireNonNull(state, "state");
            jobId = optionalText(jobId, "jobId");
            reason = optionalText(reason, "reason");
            requireOccurrenceStatus(state, jobId, reason);
        }
    }

    /**
     * H2 权威 Occurrence 快照。
     *
     * @param identity 稳定身份
     * @param definition 触发时冻结的完整 Definition
     * @param scheduledFor 原计划触发时间
     * @param status 运行状态
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record Occurrence(
            OccurrenceIdentity identity,
            Definition definition,
            Instant scheduledFor,
            OccurrenceStatus status,
            Instant createdAt,
            Instant updatedAt) {
        /** 校验 Occurrence 时间。 */
        public Occurrence {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(definition, "definition");
            if (!identity.scheduleId().equals(definition.id())
                    || identity.scheduleRevision() != definition.revision()) {
                throw new IllegalArgumentException("Occurrence Definition identity differs from occurrence");
            }
            scheduledFor = ContractValidation.instant(scheduledFor, "scheduledFor");
            Objects.requireNonNull(status, "status");
            createdAt = ContractValidation.instant(createdAt, "createdAt");
            updatedAt = ContractValidation.instant(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not precede createdAt");
            }
        }
    }

    /**
     * 未来触发预览。
     *
     * @param instants 固定五个未来触发点
     */
    public record Preview(List<Instant> instants) {
        /** 校验固定数量和严格递增。 */
        public Preview {
            instants = List.copyOf(Objects.requireNonNull(instants, "instants"));
            if (instants.size() != 5) {
                throw new IllegalArgumentException("Schedule preview requires exactly five instants");
            }
            for (int index = 1; index < instants.size(); index++) {
                if (!instants.get(index).isAfter(instants.get(index - 1))) {
                    throw new IllegalArgumentException("Schedule preview instants must increase");
                }
            }
        }
    }

    /**
     * Definition 事务写入的投影 Outbox 消息。
     *
     * @param workspaceId Workspace
     * @param scheduleId Schedule 标识
     * @param definition 新 Definition；删除时为空
     * @param sourceRevision 本次 Definition 或 tombstone revision
     */
    public record ProjectionChange(
            WorkspaceId workspaceId, String scheduleId, Optional<Definition> definition, long sourceRevision) {
        /** 校验投影消息。 */
        public ProjectionChange {
            Objects.requireNonNull(workspaceId, "workspaceId");
            scheduleId = ContractValidation.text(scheduleId, "scheduleId");
            definition = Objects.requireNonNull(definition, "definition");
            sourceRevision = ContractValidation.revision(sourceRevision);
            if (definition.isPresent()) {
                Definition value = definition.orElseThrow();
                if (!value.id().equals(scheduleId) || value.revision() != sourceRevision) {
                    throw new IllegalArgumentException("projection definition identity differs from message");
                }
            }
        }
    }

    /**
     * 手动运行固定 Definition revision。
     *
     * @param scheduleId Schedule 标识
     */
    public record ManualRun(String scheduleId) {
        /** 校验标识。 */
        public ManualRun {
            scheduleId = ContractValidation.text(scheduleId, "scheduleId");
        }
    }

    /**
     * 读取固定 Definition revision 的未来五次触发时间。
     *
     * @param scheduleId Schedule 标识
     * @param scheduleRevision Schedule revision
     * @param after 只返回此时间之后的触发点
     */
    public record PreviewRequest(String scheduleId, long scheduleRevision, Instant after) {
        /** 校验查询身份。 */
        public PreviewRequest {
            scheduleId = ContractValidation.text(scheduleId, "scheduleId");
            scheduleRevision = ContractValidation.revision(scheduleRevision);
            after = ContractValidation.instant(after, "after");
        }
    }

    /**
     * Quartz 向扩展命令门禁提交的确定性触发点。
     *
     * @param scheduleId Schedule 标识
     * @param scheduleRevision 触发器冻结的 revision
     * @param scheduledFor Quartz 原计划触发时间
     */
    public record DeliveryRequest(String scheduleId, long scheduleRevision, Instant scheduledFor) {
        /** 校验投递身份。 */
        public DeliveryRequest {
            scheduleId = ContractValidation.text(scheduleId, "scheduleId");
            scheduleRevision = ContractValidation.revision(scheduleRevision);
            scheduledFor = ContractValidation.instant(scheduledFor, "scheduledFor");
        }
    }

    /**
     * Occurrence 查询参数。
     *
     * @param scheduleId 可选 Schedule 过滤
     * @param afterKey 排他游标
     * @param limit 页大小
     */
    public record OccurrenceQuery(Optional<String> scheduleId, String afterKey, int limit) {
        /** 校验分页参数。 */
        public OccurrenceQuery {
            scheduleId = optionalText(scheduleId, "scheduleId");
            afterKey = Objects.requireNonNull(afterKey, "afterKey");
            if (limit < 1 || limit > 500) {
                throw new IllegalArgumentException("limit must be between 1 and 500");
            }
        }
    }

    /**
     * Occurrence 分页结果。
     *
     * @param occurrences 稳定键排序结果
     * @param nextKey 下一游标；结束时为空
     */
    public record OccurrencePage(List<Occurrence> occurrences, String nextKey) {
        /** 复制结果。 */
        public OccurrencePage {
            occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
            nextKey = Objects.requireNonNull(nextKey, "nextKey");
        }
    }

    /**
     * Schedule Occurrence Job 的冻结输入。
     *
     * @param definition 触发时的完整 Schedule Definition
     * @param occurrenceId 独立 Occurrence ID
     * @param executionSnapshot Turn 模板使用的平台权威快照；其他目标为空
     */
    public record ScheduledExecution(
            Definition definition, String occurrenceId, Optional<AutomationExecutionSnapshot> executionSnapshot) {
        /** 校验冻结输入与目标类别一致。 */
        public ScheduledExecution {
            Objects.requireNonNull(definition, "definition");
            occurrenceId = ContractValidation.text(occurrenceId, "occurrenceId");
            executionSnapshot = Objects.requireNonNull(executionSnapshot, "executionSnapshot");
            boolean needsSnapshot = definition.target().kind() == TargetKind.TURN_TEMPLATE;
            if (needsSnapshot != executionSnapshot.isPresent()) {
                throw new IllegalArgumentException("Schedule execution snapshot does not match target kind");
            }
        }
    }

    /**
     * Occurrence Job 恢复指针。
     *
     * @param completed 目标是否已安全投递或完成
     */
    public record OccurrenceCheckpoint(boolean completed) {}

    private static void requireOccurrenceStatus(
            OccurrenceState state, Optional<String> jobId, Optional<String> reason) {
        boolean needsJob = state == OccurrenceState.DISPATCHED || state == OccurrenceState.RUNNING;
        boolean needsReason = state == OccurrenceState.SKIPPED || state == OccurrenceState.FAILED;
        if ((needsJob && jobId.isEmpty()) || needsReason != reason.isPresent()) {
            throw new IllegalArgumentException("Occurrence status fields are inconsistent");
        }
    }

    private static Optional<String> optionalText(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name).map(entry -> ContractValidation.text(entry, name));
    }
}
