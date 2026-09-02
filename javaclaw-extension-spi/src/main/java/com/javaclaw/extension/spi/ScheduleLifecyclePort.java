package com.javaclaw.extension.spi;

import com.javaclaw.api.WorkspaceId;

/** 同步启用 Schedule 对 App Server 长期 lease 与登录启动项的需求。 */
@FunctionalInterface
public interface ScheduleLifecyclePort {
    /**
     * 同步一个 Workspace 是否仍有启用的 Schedule Definition。
     *
     * @param workspaceId Workspace
     * @param required 至少一个 Schedule 启用时为 {@code true}
     */
    void synchronize(WorkspaceId workspaceId, boolean required);

    /**
     * 创建始终安全拒绝的端口。
     *
     * @return 未装配后台生命周期协调器的实现
     */
    static ScheduleLifecyclePort unavailable() {
        return (workspaceId, required) -> {
            throw new IllegalStateException("Schedule lifecycle port is unavailable");
        };
    }
}
