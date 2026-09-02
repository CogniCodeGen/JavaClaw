package com.javaclaw.runtime;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;

/** 审批、Sandbox、实时撤权和 EffectReceipt 之后的工具执行端口。 */
@FunctionalInterface
public interface GovernedToolExecutor {
    /**
     * 执行工具；实现必须在副作用前重新检查 enabled、revision、撤权与 PermissionProfile。
     *
     * @param request 已匹配冻结目录的请求
     * @param descriptor 冻结描述
     * @param snapshot Turn 启动时冻结的完整目录
     * @param permissions 有效权限
     * @param cancellation 取消信号
     * @return 脱敏结果与可选目录展开
     * @throws Exception 审批拒绝、Sandbox 或工具失败
     */
    ToolExecutionOutcome execute(
            ToolCallRequest request,
            ToolDescriptor descriptor,
            ToolCatalogSnapshot snapshot,
            PermissionProfile permissions,
            CancellationToken cancellation)
            throws Exception;
}
