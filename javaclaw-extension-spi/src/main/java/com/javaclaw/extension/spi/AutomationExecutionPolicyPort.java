package com.javaclaw.extension.spi;

import java.util.List;

import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.WorkspaceId;

/** 自动化 Execution 的 Role、模型、权限、预算与工具目录权威冻结端口。 */
public interface AutomationExecutionPolicyPort {
    /**
     * 列出可供新自动化 Execution 选择的活动 Agent Role 精确版本。
     *
     * <p>默认实现安全拒绝，避免未绑定的启动期端口或测试替身静默返回空目录。
     *
     * @param workspaceId 所属 Workspace
     * @return 按用户可见名称和稳定标识排序的不可变目录
     */
    default List<AutomationRoleOption> roles(WorkspaceId workspaceId) {
        throw new IllegalStateException("Automation execution policy port does not provide a role catalog");
    }

    /**
     * 列出可用于自动化独立模型选择的 Provider 最新活动版本。
     *
     * @param workspaceId 所属 Workspace，服务端必须校验存在性
     * @return 不包含凭据正文的不可变 Provider 目录
     */
    default List<ProviderEndpoint> providers(WorkspaceId workspaceId) {
        throw new IllegalStateException("Automation execution policy port does not provide a provider catalog");
    }

    /**
     * 列出可用于独立权限选择的最新权限配置。
     *
     * @param workspaceId 所属 Workspace，服务端必须校验存在性
     * @return 不可变权限配置目录，选择仍不代表执行授权
     */
    default List<PermissionProfile> permissions(WorkspaceId workspaceId) {
        throw new IllegalStateException("Automation execution policy port does not provide a permission catalog");
    }

    /**
     * 从服务端权威配置生成不可扩权的执行快照。
     *
     * @param workspaceId 所属 Workspace
     * @param execution 客户端独立选择的 Role、模型、权限和可收窄执行选项
     * @param cancellation 取消信号
     * @return 平台解析并冻结的执行快照
     */
    AutomationExecutionSnapshot freeze(
            WorkspaceId workspaceId, ExecutionOverrides execution, CancellationToken cancellation);
}
