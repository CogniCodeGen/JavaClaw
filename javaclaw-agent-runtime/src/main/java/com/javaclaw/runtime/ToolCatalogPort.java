package com.javaclaw.runtime;

import java.util.List;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

/** Turn 工具目录冻结与渐进发现端口。 */
public interface ToolCatalogPort {
    /**
     * 冻结来源、版本和权限上限。
     *
     * @param turnId Turn 标识
     * @param permissions 有效权限
     * @param cancellation 取消信号
     * @return 冻结目录
     */
    ToolCatalogSnapshot freeze(TurnId turnId, PermissionProfile permissions, CancellationToken cancellation);

    /**
     * 按 Workspace 冻结目录；需要 Workspace 归属的工具来源应覆写该方法。
     *
     * @param turnId Turn 标识
     * @param workspaceId Workspace 标识
     * @param permissions 有效权限
     * @param cancellation 取消信号
     * @return 冻结目录
     */
    default ToolCatalogSnapshot freeze(
            TurnId turnId, WorkspaceId workspaceId, PermissionProfile permissions, CancellationToken cancellation) {
        return freeze(turnId, permissions, cancellation);
    }

    /**
     * 将自动化 Execution 的权威冻结目录绑定到新的子 Turn。
     *
     * <p>实现必须对比实时目录与冻结目录，任一来源 revision、Schema、禁用或撤权变化都应失败关闭；不能把实时新增工具加入结果。
     *
     * @param turnId 新子 Turn 标识
     * @param workspaceId Workspace
     * @param frozen Execution 启动时的权威目录
     * @param currentPermissions 当前只可收窄的有效权限
     * @param cancellation 取消信号
     * @return 只更换 Turn 身份的冻结目录
     */
    default ToolCatalogSnapshot bindFrozen(
            TurnId turnId,
            WorkspaceId workspaceId,
            ToolCatalogSnapshot frozen,
            PermissionProfile currentPermissions,
            CancellationToken cancellation) {
        throw new UnsupportedOperationException("frozen automation catalogs are not supported");
    }

    /**
     * 返回首轮公开的 Core 工具和搜索入口。
     *
     * @param snapshot 冻结目录
     * @return 冻结目录的子集
     */
    List<ToolDescriptor> initialTools(ToolCatalogSnapshot snapshot);
}
