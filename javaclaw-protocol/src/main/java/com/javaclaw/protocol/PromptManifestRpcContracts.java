package com.javaclaw.protocol;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/** 下一 Turn 分层 Prompt 的只读预览契约。 */
public final class PromptManifestRpcContracts {
    private PromptManifestRpcContracts() {}

    /**
     * 在服务端统一解析独立执行选择后预览来源。
     *
     * @param workspaceId 项目约定所属 Workspace
     * @param threadId 可选 Thread，用于解析 Thread 直接覆盖
     * @param execution 本次预览的显式执行选择
     */
    public record PreviewPayload(WorkspaceId workspaceId, Optional<ThreadId> threadId, ExecutionOverrides execution) {
        /** 校验范围和选择。 */
        public PreviewPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            threadId = Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(execution, "execution");
        }
    }
}
