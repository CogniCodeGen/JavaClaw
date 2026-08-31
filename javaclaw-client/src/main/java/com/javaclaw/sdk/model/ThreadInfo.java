package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * Thread 持久状态、工作区归属与恢复游标的公开视图。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param workspaceId 所属 Workspace 标识
 * @param parentThreadId 父 Thread 标识；根 Thread 为 null
 * @param forkedFromTurnId 分支起点 Turn；非分支 Thread 可为 null
 * @param title 展示标题
 * @param status 服务端生命周期或执行结果状态
 * @param baseSequence 分支基础持久 sequence，非负
 * @param lastSequence 最近持久事件 sequence，作为重连游标
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param createdAt 创建时间；有效持久记录中非空
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record ThreadInfo(
        String id,
        String workspaceId,
        String parentThreadId,
        String forkedFromTurnId,
        String title,
        String status,
        long baseSequence,
        long lastSequence,
        long revision,
        Instant createdAt,
        Instant updatedAt) {}
