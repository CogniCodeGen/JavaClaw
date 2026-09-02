package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Workspace 项目约定解析设置的不可变快照。
 *
 * @param workspaceId 所属 Workspace
 * @param fallbackBasename 在 {@code AGENTS.override.md} 与 {@code AGENTS.md} 都不存在时尝试的安全文件名
 * @param revision 设置自身的乐观锁版本，从 1 开始
 * @param updatedAt 最近更新时间
 */
public record WorkspaceInstructionSettings(
        WorkspaceId workspaceId, Optional<String> fallbackBasename, long revision, Instant updatedAt) {
    /** 校验 Workspace、文件名和 revision。 */
    public WorkspaceInstructionSettings {
        Objects.requireNonNull(workspaceId, "workspaceId");
        fallbackBasename = Objects.requireNonNull(fallbackBasename, "fallbackBasename")
                .map(WorkspaceInstructionSettings::checkedBasename);
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String checkedBasename(String value) {
        String checked = Objects.requireNonNull(value, "fallbackBasename").strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            throw new IllegalArgumentException("fallbackBasename must be a safe basename");
        }
        return checked;
    }
}
