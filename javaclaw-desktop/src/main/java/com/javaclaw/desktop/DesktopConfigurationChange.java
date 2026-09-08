package com.javaclaw.desktop;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/**
 * 当前 Desktop 会话中成功写入后的配置失效提示，不包含密钥或业务配置正文。
 *
 * @param kind 需要重新读取的资源类别，不能为空
 * @param workspaceId 受影响工作区；空容器表示全局目录或安装级配置，容器不能为空
 * @param threadId 受影响 Thread；缺省表示整个工作区或全局配置，容器不能为空
 */
public record DesktopConfigurationChange(Kind kind, Optional<WorkspaceId> workspaceId, Optional<ThreadId> threadId) {
    /** 校验失效范围；Thread 范围必须同时包含工作区。 */
    public DesktopConfigurationChange {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        if (threadId.isPresent() && workspaceId.isEmpty()) {
            throw new IllegalArgumentException("Thread 配置失效必须包含工作区");
        }
    }

    /** 用于选择性重读目录和执行配置的资源类别。 */
    public enum Kind {
        /** 工作区登记目录。 */
        WORKSPACES,
        /** 模型服务、模型目录及其凭据状态。 */
        PROVIDERS,
        /** Agent 角色目录。 */
        ROLES,
        /** 权限配置目录。 */
        PERMISSIONS,
        /** 安装、工作区或 Thread 的直接执行配置。 */
        EXECUTION
    }
}
