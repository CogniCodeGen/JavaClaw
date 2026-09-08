package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/**
 * 模型配置入口固定的使用目标，不随其他窗口的选择变化。
 *
 * @param workspaceId 目标工作区；尚未选择时为空
 * @param threadId 入口对话；设置入口为空，由主窗口选择属于目标工作区的对话
 * @param workspaceName 目标工作区显示名称；无工作区时为空文本
 */
public record ProviderSetupTarget(
        Optional<WorkspaceId> workspaceId, Optional<ThreadId> threadId, String workspaceName) {
    /** 校验目标；无工作区时不允许携带对话标识。 */
    public ProviderSetupTarget {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        workspaceName = Objects.requireNonNullElse(workspaceName, "");
        if (workspaceId.isEmpty() && threadId.isPresent()) {
            throw new IllegalArgumentException("对话使用目标必须包含工作区");
        }
    }

    /** @return 清晰标明目标的保存按钮文案 */
    public String saveLabel() {
        return workspaceId.isPresent() ? "保存并在「" + workspaceName + "」中使用" : "保存并使用";
    }
}
