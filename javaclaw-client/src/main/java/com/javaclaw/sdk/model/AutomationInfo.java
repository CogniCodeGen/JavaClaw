package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * 自动化定义、修订号及其最近执行绑定的公开快照。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param kind 稳定类型标记；读取方应保留或忽略未知种类
 * @param name 展示名称或资源名称；有效服务端响应中非空
 * @param workspaceId 所属 Workspace 标识
 * @param profileId 服务端 Profile 标识；不允许客户端替换最终权限策略
 * @param prompt 展示问题或执行提示词；不能被用作权限声明
 * @param definition 结构化自动化定义；不包含客户端执行权限
 * @param status 服务端生命周期或执行结果状态
 * @param threadId 自动化绑定的稳定 Thread；首次启动前可为 null
 * @param activeTurnId 当前绑定的活动 Turn；没有活动执行时可为 null
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param createdAt 创建时间；有效持久记录中非空
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record AutomationInfo(
        String id,
        String kind,
        String name,
        String workspaceId,
        String profileId,
        String prompt,
        JsonDocument definition,
        String status,
        String threadId,
        String activeTurnId,
        long revision,
        Instant createdAt,
        Instant updatedAt) {}
