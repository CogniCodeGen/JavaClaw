package com.javaclaw.extension.spi;

import java.util.List;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.WorkspaceId;

/** 自动化 Execution 的 Profile、权限、预算与工具目录权威冻结端口。 */
public interface AutomationExecutionPolicyPort {
    /**
     * 列出可供新自动化 Execution 选择的活动 Agent Profile 精确版本。
     *
     * <p>默认实现安全拒绝，避免未绑定的启动期端口或测试替身静默返回空目录。
     *
     * @param workspaceId 所属 Workspace
     * @return 按用户可见名称和稳定标识排序的不可变目录
     */
    default List<AutomationProfileOption> profiles(WorkspaceId workspaceId) {
        throw new IllegalStateException("Automation execution policy port does not provide a profile catalog");
    }

    /**
     * 从服务端权威配置生成不可扩权的执行快照。
     *
     * @param workspaceId 所属 Workspace
     * @param profile 客户端选择的精确 Profile 引用
     * @param cancellation 取消信号
     * @return 平台解析并冻结的执行快照
     */
    AutomationExecutionSnapshot freeze(
            WorkspaceId workspaceId, AgentProfileRef profile, CancellationToken cancellation);
}
