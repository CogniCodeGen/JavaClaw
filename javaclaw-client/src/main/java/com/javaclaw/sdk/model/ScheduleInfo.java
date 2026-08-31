package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * Schedule 的持久定义及触发状态，重叠按 SKIP 处理，错过的触发不补跑。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param name 展示名称或资源名称；有效服务端响应中非空
 * @param workspaceId 所属 Workspace 标识
 * @param threadId Schedule 绑定的稳定 Thread；尚未绑定时可为 null
 * @param profileId 服务端 Profile 标识；不允许客户端替换最终权限策略
 * @param prompt 展示问题或执行提示词；不能被用作权限声明
 * @param cronExpression Quartz cron 表达式
 * @param zoneId 调度使用的 IANA 时区标识
 * @param enabled 是否启用；禁用不会删除历史记录
 * @param nextFireAt 下次触发时间；未安排或无后续触发时可为 null
 * @param lastFireAt 最近触发时间；尚未触发时可为 null
 * @param lastResult 最近触发或投影安排结果；尚无结果时可为空
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param createdAt 创建时间；有效持久记录中非空
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record ScheduleInfo(
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
