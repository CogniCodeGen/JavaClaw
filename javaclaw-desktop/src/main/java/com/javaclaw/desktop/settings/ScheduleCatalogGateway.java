package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.ScheduleContracts;

/** 设置中心读取 Schedule 精确定义目录的强类型 SDK 边界。 */
public interface ScheduleCatalogGateway {
    /**
     * 读取固定 Workspace 中最多一千条当前 Schedule 定义。
     *
     * @param workspaceId 固定 Workspace
     * @return 稳定标识排序的当前定义
     */
    CompletionStage<List<ScheduleContracts.Definition>> schedules(WorkspaceId workspaceId);
}
