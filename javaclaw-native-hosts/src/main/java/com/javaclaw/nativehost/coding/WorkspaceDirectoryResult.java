package com.javaclaw.nativehost.coding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 单次目录 Worker 的可信回执；失败时仍保留已经发生的变化，进程丢失不能自行构造此回执。
 *
 * @param changes 已实际创建或移走的目录，不可空
 * @param failureCode 稳定失败分类，完整成功时为空
 * @param recoveryPaths 保留原目录对象的恢复路径，不可空
 */
public record WorkspaceDirectoryResult(List<Change> changes, Optional<String> failureCode, List<String> recoveryPaths) {
    /** 固定有界回执。 */
    public WorkspaceDirectoryResult {
        changes = List.copyOf(changes);
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        recoveryPaths = List.copyOf(recoveryPaths);
        if (changes.size() > 200 || recoveryPaths.size() > 200) {
            throw new IllegalArgumentException("directory result exceeds limit");
        }
        recoveryPaths.forEach(path -> WorkspaceFileProtocol.requireRelative(path, false));
    }

    /**
     * @param path 已发生变化的相对目录
     * @param operation create 或 delete
     */
    public record Change(String path, String operation) {
        /** 校验来源 Worker 的目录变化。 */
        public Change {
            WorkspaceFileProtocol.requireRelative(path, false);
            if (!List.of("create", "delete").contains(operation)) {
                throw new IllegalArgumentException("unknown directory operation");
            }
        }
    }
}
