package com.javaclaw.agent.automation;

import java.util.List;

import com.javaclaw.core.api.AgentTurn;

/** Automation and Schedule administration boundary. */
public interface AutomationUseCases {
    /** 列出已保存的自动化定义及其最近执行状态，不触发执行。 */
    List<AutomationRepository.AutomationDefinition> listAutomations();

    /** 读取自动化定义；不存在时抛出 NoSuchElementException。 */
    AutomationRepository.AutomationDefinition readAutomation(String id);

    /** 创建或按预期修订号更新自动化定义；幂等键防止重复写入，不启动 Turn。 */
    AutomationRepository.AutomationDefinition putAutomation(
            AutomationRepository.AutomationDraft draft, long revision, String idempotencyKey);

    /** 按版本删除自动化定义并返回是否删除成功；不隐式删除绑定 Thread 的历史。 */
    boolean deleteAutomation(String id, long revision, String idempotencyKey);

    /** 解析对应 Profile，在已有或新建的稳定 Thread 启动 Turn 并记录绑定；返回启动快照，活动冲突由 Runtime 拒绝。 */
    AgentTurn startAutomation(String id, String idempotencyKey);

    /** 从已确认检查点创建新 Turn；复用逻辑执行标识并扣除旧预算，定义改变或预算耗尽时拒绝。 */
    AgentTurn resumeAutomation(String id, String idempotencyKey);

    /** 查询绑定 Thread 的检查点、产物及评估历史；不触发模型或外部操作。 */
    List<com.javaclaw.core.api.StoredItem> executionItems(String id);

    /** 中断绑定的活动 Turn 并保存 INTERRUPTED 状态；没有可取消执行时返回 false。 */
    boolean interruptAutomation(String id);

    /** 列出持久 Schedule 定义；Quartz 内存状态不是查询权威。 */
    List<AutomationRepository.ScheduleDefinition> listSchedules();

    /** 读取 Schedule 定义；不存在时抛出 NoSuchElementException。 */
    AutomationRepository.ScheduleDefinition readSchedule(String id);

    /** 校验未保存的 Quartz Cron 和 IANA 时区并计算未来触发时间；count 限制为 1–20，不读取或写入 Schedule 状态。 */
    default List<java.time.Instant> previewSchedule(String cronExpression, String zoneId, int count) {
        return SchedulePreviews.calculate(cronExpression, zoneId, count, java.time.Instant.now());
    }

    /** 校验 cron、时区和无人值守 Profile 后保存 Schedule；拒绝 HOST_FULL_ACCESS，返回新修订。 */
    AutomationRepository.ScheduleDefinition putSchedule(
            AutomationRepository.ScheduleDraft draft, long revision, String idempotencyKey);

    /** 按版本保存 Schedule 的启停状态；幂等重放返回对应结果，不立即触发一次执行。 */
    AutomationRepository.ScheduleDefinition setScheduleEnabled(
            String id, boolean enabled, long revision, String idempotencyKey);

    /** 按版本删除 Schedule 定义并返回删除结果；稳定 Thread 的历史不随定义一同删除。 */
    boolean deleteSchedule(String id, long revision, String idempotencyKey);

    /** 手动触发一次 Schedule；活动 Thread 固定采用 SKIP 重叠策略，无人值守权限上限始终有效。 */
    AutomationService.TriggerResult triggerSchedule(String id, String idempotencyKey);
}
