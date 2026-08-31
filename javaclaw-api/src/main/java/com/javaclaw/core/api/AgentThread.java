package com.javaclaw.core.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

import com.javaclaw.sandbox.api.SandboxPaths;

/**
 * Thread 的持久状态快照；分支关系和事件游标一同保存，目录由 Workspace 决定。
 *
 * @param id Thread 的非空标识
 * @param workspaceId 非空白 Workspace 标识
 * @param parentThreadId 父 Thread 标识；根 Thread 为 null
 * @param forkedFromTurnId 分支起点 Turn；非分支 Thread 可为 null
 * @param title 展示标题；null 归一为空字符串
 * @param workingDirectory 规范化后的工作目录；非空，解析已存在祖先的符号链接
 * @param status 非空生命周期状态
 * @param baseSequence 分支基础事件序号，非负
 * @param lastSequence 当前最后一个持久事件序号，非负；token delta 不消耗此序号
 * @param revision 从 1 开始的资源修订号，用于乐观锁
 * @param createdAt 创建时间，非空
 * @param updatedAt 最近更新时间，非空
 */
public record AgentThread(
        ThreadId id,
        String workspaceId,
        ThreadId parentThreadId,
        TurnId forkedFromTurnId,
        String title,
        Path workingDirectory,
        ThreadStatus status,
        long baseSequence,
        long lastSequence,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验标识、目录和版本范围，固定 Thread 状态快照；不读取或修改数据库。 */
    public AgentThread {
        id = Objects.requireNonNull(id, "id");
        workspaceId = ThreadId.required(workspaceId, "workspaceId");
        title = title == null ? "" : title.strip();
        workingDirectory = SandboxPaths.canonicalize(workingDirectory);
        status = Objects.requireNonNull(status, "status");
        if (baseSequence < 0) {
            throw new IllegalArgumentException("baseSequence must be non-negative");
        }
        if (lastSequence < 0) {
            throw new IllegalArgumentException("lastSequence must be non-negative");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
