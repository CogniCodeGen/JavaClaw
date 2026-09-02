package com.javaclaw.protocol;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;

/** 项目约定脱敏解析结果的 Protocol v2 DTO。 */
public final class InstructionRpcContracts {
    private InstructionRpcContracts() {}

    /**
     * @param workspaceId Workspace
     * @param worktreeId 可选受管 Worktree；为空时解析 Workspace 根
     */
    public record ReadPayload(WorkspaceId workspaceId, Optional<WorktreeId> worktreeId) {
        /** 校验作用域引用。 */
        public ReadPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            worktreeId = Objects.requireNonNull(worktreeId, "worktreeId");
        }
    }

    /** @param workspaceId Workspace */
    public record SettingsReadPayload(WorkspaceId workspaceId) {
        /** 校验 Workspace。 */
        public SettingsReadPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    /**
     * @param workspaceId Workspace
     * @param fallbackBasename 安全备用文件名；空值表示关闭 fallback
     */
    public record SettingsUpdatePayload(WorkspaceId workspaceId, Optional<String> fallbackBasename) {
        /** 校验 Workspace 与 Optional 容器；具体 basename 由公共契约校验。 */
        public SettingsUpdatePayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            fallbackBasename = Objects.requireNonNull(fallbackBasename, "fallbackBasename");
        }
    }
}
