package com.javaclaw.builtin.contracts;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ExecutionOverrides;

/** Schedule 管理中心的强类型写入契约。 */
public final class ScheduleManagementContracts {
    /** 平台内置 TurnTemplate 目标在权威目录中的所有者标识。 */
    public static final String TURN_TEMPLATE_EXTENSION = "javaclaw.schedule.platform";

    /** 平台内置 TurnTemplate 目标在权威目录中的稳定标识。 */
    public static final String TURN_TEMPLATE_ID = "turn-template";

    private static final int MAXIMUM_ID_LENGTH = 100;
    private static final int MAXIMUM_NAME_LENGTH = 200;
    private static final int MAXIMUM_CRON_LENGTH = 256;
    private static final int MAXIMUM_ZONE_LENGTH = 128;
    private static final int MAXIMUM_TITLE_LENGTH = 200;
    private static final int MAXIMUM_INSTRUCTION_LENGTH = 32_768;
    private static final long MAXIMUM_INTERVAL_MINUTES = 525_600;

    private ScheduleManagementContracts() {}

    /**
     * 创建或编辑一个 Schedule Definition。
     *
     * <p>Role、Provider 和 PermissionProfile 分别使用权威目录中的精确版本。revision、固定调度策略和更新时间由服务端生成，客户端契约故意不包含这些字段。
     *
     * @param id Schedule 标识；编辑时由权威详情数据源绑定
     * @param name 用户可见名称
     * @param enabled 是否允许创建新 Occurrence
     * @param timingKind Cron 或固定间隔
     * @param targetKind 权威目录绑定的目标类别
     * @param targetExtensionId 权威目录绑定的目标扩展
     * @param targetId Definition 标识或 Action operation
     * @param targetRevision Definition 或 Action 的精确版本
     * @param targetSchemaHash Action 输入 Schema SHA-256；其他目标为空字符串
     * @param cronExpression Quartz Cron；仅 Cron 存在
     * @param zoneId IANA Zone；仅 Cron 存在
     * @param intervalMinutes 固定间隔分钟数；仅固定间隔存在
     * @param firstFireAt 首次触发时间；仅固定间隔存在
     * @param execution 独立 Role、模型、权限与审批选择
     * @param title 新 Thread 标题
     * @param instruction 冻结到 Occurrence 的 Turn 指令
     * @param maximumTurns Execution 最大 Turn 数
     * @param inputTokens 输入 token 总上限
     * @param outputTokens 输出 token 总上限
     * @param toolCalls Tool 调用总上限
     * @param actionArguments SchedulableAction 的完整固定参数；其他目标为空
     */
    public record SaveRequest(
            String id,
            String name,
            boolean enabled,
            ScheduleContracts.TimingKind timingKind,
            ScheduleContracts.TargetKind targetKind,
            String targetExtensionId,
            String targetId,
            long targetRevision,
            String targetSchemaHash,
            Optional<String> cronExpression,
            Optional<String> zoneId,
            Optional<Long> intervalMinutes,
            Optional<Instant> firstFireAt,
            ExecutionOverrides execution,
            String title,
            String instruction,
            int maximumTurns,
            long inputTokens,
            long outputTokens,
            int toolCalls,
            java.util.List<ScheduleActionContracts.Argument> actionArguments) {
        /** 校验管理输入的互斥时间字段、权威引用形状和有限预算。 */
        public SaveRequest {
            id = boundedText(id, "id", MAXIMUM_ID_LENGTH);
            name = boundedText(name, "name", MAXIMUM_NAME_LENGTH);
            Objects.requireNonNull(timingKind, "timingKind");
            Objects.requireNonNull(targetKind, "targetKind");
            targetExtensionId = boundedText(targetExtensionId, "targetExtensionId", MAXIMUM_ID_LENGTH);
            targetId = boundedText(targetId, "targetId", MAXIMUM_ID_LENGTH);
            if (targetRevision < 0 || targetKind == ScheduleContracts.TargetKind.DEFINITION && targetRevision < 1) {
                throw new IllegalArgumentException("targetRevision does not match targetKind");
            }
            targetSchemaHash = normalizeTargetSchemaHash(targetKind, targetSchemaHash);
            cronExpression = optionalText(cronExpression, "cronExpression", MAXIMUM_CRON_LENGTH);
            zoneId = optionalText(zoneId, "zoneId", MAXIMUM_ZONE_LENGTH);
            intervalMinutes = Objects.requireNonNull(intervalMinutes, "intervalMinutes");
            firstFireAt = Objects.requireNonNull(firstFireAt, "firstFireAt");
            Objects.requireNonNull(execution, "execution");
            title = boundedText(title, "title", MAXIMUM_TITLE_LENGTH);
            instruction = boundedText(instruction, "instruction", MAXIMUM_INSTRUCTION_LENGTH);
            new OrchestrationContracts.ExecutionBudget(maximumTurns, inputTokens, outputTokens, toolCalls);
            actionArguments = java.util.List.copyOf(Objects.requireNonNull(actionArguments, "actionArguments"));
            if (actionArguments.size() > 32) {
                throw new IllegalArgumentException("actionArguments exceeds 32 fields");
            }
            buildTiming(timingKind, cronExpression, zoneId, intervalMinutes, firstFireAt);
        }

        /**
         * 构造经基础契约校验的时间配置。
         *
         * @return Cron 或固定间隔配置
         */
        public ScheduleContracts.Timing timing() {
            return buildTiming(timingKind, cronExpression, zoneId, intervalMinutes, firstFireAt);
        }

        /**
         * 返回有限的 Execution 总预算。
         *
         * @return Execution 预算
         */
        public OrchestrationContracts.ExecutionBudget budget() {
            return new OrchestrationContracts.ExecutionBudget(maximumTurns, inputTokens, outputTokens, toolCalls);
        }

        /**
         * 由服务端版本和时钟生成权威 Definition。
         *
         * @param revision 新文档版本
         * @param updatedAt 服务端更新时间
         * @return 含经权威目录确认目标的 Definition
         */
        public ScheduleContracts.Definition definition(
                ScheduleContracts.Target target, long revision, Instant updatedAt) {
            return new ScheduleContracts.Definition(
                    id,
                    revision,
                    name,
                    enabled,
                    timing(),
                    Objects.requireNonNull(target, "target"),
                    ScheduleContracts.OverlapPolicy.SKIP_IF_RUNNING,
                    ScheduleContracts.MisfirePolicy.DO_NOT_CATCH_UP,
                    Objects.requireNonNull(updatedAt, "updatedAt"));
        }
    }

    private static ScheduleContracts.Timing buildTiming(
            ScheduleContracts.TimingKind kind,
            Optional<String> cronExpression,
            Optional<String> zoneId,
            Optional<Long> intervalMinutes,
            Optional<Instant> firstFireAt) {
        return switch (kind) {
            case CRON ->
                ScheduleContracts.Timing.cron(
                        cronExpression.orElseThrow(() -> new IllegalArgumentException("cronExpression is required")),
                        zoneId.orElseThrow(() -> new IllegalArgumentException("zoneId is required")));
            case FIXED_INTERVAL ->
                ScheduleContracts.Timing.fixed(
                        Duration.ofMinutes(interval(intervalMinutes)),
                        firstFireAt.orElseThrow(() -> new IllegalArgumentException("firstFireAt is required")));
        };
    }

    private static long interval(Optional<Long> value) {
        long minutes = value.orElseThrow(() -> new IllegalArgumentException("intervalMinutes is required"));
        if (minutes < 1 || minutes > MAXIMUM_INTERVAL_MINUTES) {
            throw new IllegalArgumentException("intervalMinutes is outside the allowed range");
        }
        return minutes;
    }

    private static String boundedText(String value, String name, int maximumLength) {
        String checked = ContractValidation.text(value, name);
        if (checked.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds its length limit");
        }
        return checked;
    }

    private static Optional<String> optionalText(Optional<String> value, String name, int maximumLength) {
        return Objects.requireNonNull(value, name).map(text -> boundedText(text, name, maximumLength));
    }

    private static String normalizeTargetSchemaHash(ScheduleContracts.TargetKind kind, String value) {
        String normalized =
                Objects.requireNonNull(value, "targetSchemaHash").strip().toLowerCase(java.util.Locale.ROOT);
        if (kind == ScheduleContracts.TargetKind.ACTION) {
            if (!normalized.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Action targetSchemaHash must be a SHA-256 digest");
            }
            return normalized;
        }
        if (!normalized.isEmpty()) {
            throw new IllegalArgumentException("non-Action targetSchemaHash must be empty");
        }
        return normalized;
    }
}
