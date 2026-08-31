package com.javaclaw.agent.tool;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** 有范围、额度和版本约束的无人值守授权端口；仅授权一次操作，不改变 SandboxPolicy。 */
@FunctionalInterface
public interface ToolAuthorizationGateway {
    ToolAuthorizationGateway DENY_ALL = (context, tool, arguments, policy, invocationId) -> Optional.empty();

    /**
     * 执行前原子核销一次许可；拒绝返回 empty，故障抛出异常且不得回退为无审批执行。 已核销后外部失败也不自动返还额度，避免未知结果导致重复发送。
     *
     * @param context 当前已固化的 Turn 和调用
     * @param tool 完成来源校验的工具快照
     * @param arguments Hook 后重新通过 Schema 校验的参数
     * @param policy 已求交集的策略，不得扩大
     * @param invocationId 稳定操作标识，用于核销去重
     * @return 仅包含授权标识、版本和剩余额度的执行凭据
     */
    Optional<Receipt> consume(
            ToolExecutionContext context,
            RegisteredTool tool,
            JsonNode arguments,
            SandboxPolicy policy,
            String invocationId)
            throws Exception;

    /**
     * 预授权核销凭据，不包含收件正文、凭据或工具参数。
     *
     * @param authorizationId 持久授权标识
     * @param revision 被核销的授权版本
     * @param remainingUses 扣除本次后的剩余次数，非负
     */
    record Receipt(String authorizationId, long revision, int remainingUses) {}
}
