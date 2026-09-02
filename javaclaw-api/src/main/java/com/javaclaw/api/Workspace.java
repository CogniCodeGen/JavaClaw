package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * 本机 Workspace 的不可变快照。
 *
 * @param id Workspace 标识
 * @param name 用户可见名称
 * @param root 规范化绝对路径
 * @param lifecycle 当前登记生命周期
 * @param revision 乐观锁版本，从 1 开始
 * @param createdAt 创建时间
 * @param updatedAt 最近修改时间
 */
public record Workspace(
        WorkspaceId id,
        String name,
        Path root,
        WorkspaceLifecycle lifecycle,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验快照中的路径、版本和时间。 */
    public Workspace {
        Objects.requireNonNull(id, "id");
        name = Preconditions.text(name, "name");
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Objects.requireNonNull(lifecycle, "lifecycle");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
