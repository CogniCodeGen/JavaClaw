package com.javaclaw.core.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

import com.javaclaw.sandbox.api.SandboxPaths;

/**
 * Canonical workspace registered by the local user before a thread can use it.
 *
 * @param id 非空 Workspace 标识
 * @param name 非空白展示名称
 * @param root 规范化工作区根目录，非空
 * @param revision 从 1 开始的资源修订号，用于乐观锁
 * @param locked 是否因安全恢复失败等原因禁止新的写操作
 * @param lockReason 锁定原因；null 归一为空字符串
 * @param createdAt 创建时间，非空
 * @param updatedAt 最近更新时间，非空
 */
public record Workspace(
        WorkspaceId id,
        String name,
        Path root,
        long revision,
        boolean locked,
        String lockReason,
        Instant createdAt,
        Instant updatedAt) {
    /** 规范化根目录并校验修订号；只保存锁状态，不尝试解锁或改变文件权限。 */
    public Workspace {
        id = Objects.requireNonNull(id, "id");
        name = ThreadId.required(name, "name");
        root = SandboxPaths.canonicalize(root);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        lockReason = lockReason == null ? "" : lockReason.strip();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
