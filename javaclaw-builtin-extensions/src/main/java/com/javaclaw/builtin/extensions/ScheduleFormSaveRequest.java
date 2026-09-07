package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.Optional;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.builtin.contracts.ScheduleActionContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.builtin.contracts.ScheduleManagementContracts;

/**
 * Schedule 动态表单：复用 SaveRequest 的时间、目标和预算校验。
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
 * @param role 权威目录绑定的精确 Role
 * @param provider 独立选择的精确模型
 * @param permissionProfile 独立选择的精确权限配置
 * @param approvalPolicy 用户显式选择的审批策略
 * @param reasoning 用户显式选择的推理偏好
 * @param title 新 Thread 标题
 * @param instruction 冻结到 Occurrence 的 Turn 指令
 * @param maximumTurns Execution 最大 Turn 数
 * @param inputTokens 输入 token 总上限
 * @param outputTokens 输出 token 总上限
 * @param toolCalls Tool 调用总上限
 * @param actionArguments SchedulableAction 的完整固定参数；其他目标为空
 */
record ScheduleFormSaveRequest(
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
        AgentRoleRef role,
        ProviderRef provider,
        PermissionProfileRef permissionProfile,
        ApprovalPolicy approvalPolicy,
        ReasoningPreference reasoning,
        String title,
        String instruction,
        int maximumTurns,
        long inputTokens,
        long outputTokens,
        int toolCalls,
        java.util.List<ScheduleActionContracts.Argument> actionArguments) {
    ScheduleManagementContracts.SaveRequest toRequest() {
        return new ScheduleManagementContracts.SaveRequest(
                id,
                name,
                enabled,
                timingKind,
                targetKind,
                targetExtensionId,
                targetId,
                targetRevision,
                targetSchemaHash,
                cronExpression,
                zoneId,
                intervalMinutes,
                firstFireAt,
                AutomationFormContracts.execution(role, provider, permissionProfile, approvalPolicy, reasoning),
                title,
                instruction,
                maximumTurns,
                inputTokens,
                outputTokens,
                toolCalls,
                actionArguments);
    }
}
