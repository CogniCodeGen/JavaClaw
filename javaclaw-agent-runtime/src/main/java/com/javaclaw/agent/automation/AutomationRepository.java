package com.javaclaw.agent.automation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 自动化领域的权威持久化端口，由 App Server 中的 H2 适配器实现。 */
public interface AutomationRepository {
    /** 列出已保存的自动化定义及其最近执行状态，不触发执行。 */
    List<AutomationDefinition> listAutomations();

    /** 按标识查找自动化定义；不存在时返回 Optional.empty。 */
    Optional<AutomationDefinition> findAutomation(String id);

    /** 创建或按预期修订号更新自动化定义；幂等键防止重复写入，不启动 Turn。 */
    AutomationDefinition putAutomation(AutomationDraft draft, long expectedRevision, String idempotencyKey);

    /** 按版本删除自动化定义并返回是否删除成功；不隐式删除绑定 Thread 的历史。 */
    boolean deleteAutomation(String id, long expectedRevision, String idempotencyKey);

    /** 按预期版本绑定稳定 Thread、当前 Turn 和状态；turnId 可为 null 表示无活动执行。 */
    AutomationDefinition bindAutomationRun(
            String id, long expectedRevision, String threadId, String turnId, String status);

    /** 列出持久 Schedule 定义；Quartz 内存状态不是查询权威。 */
    List<ScheduleDefinition> listSchedules();

    /** 读取 Schedule 定义；不存在时返回 Optional.empty。 */
    Optional<ScheduleDefinition> findSchedule(String id);

    /** 创建或按版本保存 Schedule；持久化定义与幂等记录，Quartz 投影由上层更新。 */
    ScheduleDefinition putSchedule(ScheduleDraft draft, long expectedRevision, String idempotencyKey);

    /** 按版本保存 Schedule 的启停状态；幂等重放返回对应结果，不立即触发一次执行。 */
    ScheduleDefinition setScheduleEnabled(String id, boolean enabled, long expectedRevision, String idempotencyKey);

    /** 按版本删除 Schedule 定义并返回删除结果；稳定 Thread 的历史不随定义一同删除。 */
    boolean deleteSchedule(String id, long expectedRevision, String idempotencyKey);

    /** 按版本将 Schedule 绑定到稳定 Thread；后续触发只在该 Thread 创建新 Turn。 */
    ScheduleDefinition bindScheduleThread(String id, long expectedRevision, String threadId);

    /** 保存触发结果和下一次时间；fireTime/nextFireTime 可为空，用于仅更新投影计划或结束调度。 */
    void recordScheduleFire(String id, Instant fireTime, Instant nextFireTime, String result);

    /**
     * 尚未持久化的自动化定义；本记录不执行校验，Repository 在保存时验证并分配修订号。
     *
     * @param id 草稿标识；创建时可为空，由 Repository 分配，更新时使用已有标识
     * @param kind 自动化类别，保存时要求非空
     * @param name 展示名称；保存时要求非空白
     * @param workspaceId 所属 Workspace 标识，保存时必须对应有效工作区
     * @param profileId 执行使用的 Profile 标识，保存或解析时要求有效
     * @param prompt 自动化输入提示词，保存时要求非空白
     * @param definitionJson 自动化的结构化定义 JSON，保存时由 Repository 校验
     */
    record AutomationDraft(
            String id,
            AutomationKind kind,
            String name,
            String workspaceId,
            String profileId,
            String prompt,
            String definitionJson) {}

    /**
     * 自动化定义与最近一次执行绑定的持久投影。
     *
     * @param id 已保存定义的稳定非空标识
     * @param kind 自动化类别，保存时要求非空
     * @param name 展示名称；保存时要求非空白
     * @param workspaceId 所属 Workspace 标识，保存时必须对应有效工作区
     * @param profileId 执行使用的 Profile 标识，保存或解析时要求有效
     * @param prompt 自动化输入提示词，保存时要求非空白
     * @param definitionJson 自动化的结构化定义 JSON，保存时由 Repository 校验
     * @param status 持久执行状态文本
     * @param threadId 稳定 Thread 标识；首次执行前可为 null
     * @param activeTurnId 活动 Turn 标识；未执行或已中断时可为 null
     * @param revision 从 1 开始的持久修订号，用于乐观锁
     * @param createdAt 持久记录创建时间，已保存记录非空
     * @param updatedAt 最近持久更新时间，已保存记录非空
     */
    record AutomationDefinition(
            String id,
            AutomationKind kind,
            String name,
            String workspaceId,
            String profileId,
            String prompt,
            String definitionJson,
            String status,
            String threadId,
            String activeTurnId,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * Schedule 保存草稿；cron/时区/权限在服务边界校验，本记录不启动调度器。
     *
     * @param id 草稿标识；创建时可为空，由 Repository 分配，更新时使用已有标识
     * @param name 展示名称；保存时要求非空白
     * @param workspaceId 所属 Workspace 标识，保存时必须对应有效工作区
     * @param profileId 执行使用的 Profile 标识，保存或解析时要求有效
     * @param prompt 自动化输入提示词，保存时要求非空白
     * @param cronExpression Quartz cron 表达式，保存 Schedule 时校验
     * @param zoneId IANA 时区标识，保存 Schedule 时校验
     * @param enabled 是否启用该资源；禁用状态不应产生新执行
     */
    record ScheduleDraft(
            String id,
            String name,
            String workspaceId,
            String profileId,
            String prompt,
            String cronExpression,
            String zoneId,
            boolean enabled) {}

    /**
     * H2 权威 Schedule 投影，Quartz 内存触发器可以从此重建。
     *
     * @param id 已保存定义的稳定非空标识
     * @param name 展示名称；保存时要求非空白
     * @param workspaceId 所属 Workspace 标识，保存时必须对应有效工作区
     * @param threadId 稳定 Thread 标识；首次触发前可为 null
     * @param profileId 执行使用的 Profile 标识，保存或解析时要求有效
     * @param prompt 自动化输入提示词，保存时要求非空白
     * @param cronExpression Quartz cron 表达式，保存 Schedule 时校验
     * @param zoneId IANA 时区标识，保存 Schedule 时校验
     * @param enabled 是否启用该资源；禁用状态不应产生新执行
     * @param nextFireAt 预计下次触发时间；未安排或已无后续触发时可为 null
     * @param lastFireAt 最近触发时间；从未触发时可为 null
     * @param lastResult 最近触发/调度结果，尚无结果时可为空
     * @param revision 从 1 开始的持久修订号，用于乐观锁
     * @param createdAt 持久记录创建时间，已保存记录非空
     * @param updatedAt 最近持久更新时间，已保存记录非空
     */
    record ScheduleDefinition(
            String id,
            String name,
            String workspaceId,
            String threadId,
            String profileId,
            String prompt,
            String cronExpression,
            String zoneId,
            boolean enabled,
            Instant nextFireAt,
            Instant lastFireAt,
            String lastResult,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}
}
