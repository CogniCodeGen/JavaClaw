package com.javaclaw.server.turn;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.extension.spi.AutomationExecutionPolicyPort;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.runtime.TurnExecutionResult;

/** App Server 内部可等待终态的 Turn 调度端口；RPC 仍只使用非阻塞父接口。 */
public interface AwaitableTurnDispatcher extends TurnDispatcher, AutomationExecutionPolicyPort {
    /**
     * 使用 Execution 权威快照解析子 Turn；不得从实时目录加入新能力。
     *
     * @param request 子 Turn wire 输入
     * @param message 首条用户消息
     * @param snapshot Execution 启动时的权威快照
     * @return 可持久化 Turn 参数
     */
    com.javaclaw.server.persistence.TurnStartRequest resolveOrchestrated(
            CoreRpcContracts.TurnStartPayload request,
            CorePayloads.Message message,
            AutomationExecutionSnapshot snapshot);

    /**
     * 幂等提交 Turn 并等待终态。
     *
     * @param turn 已持久化 Turn
     * @param request 执行参数
     * @param cancellation 上层取消信号
     * @return 终态结果
     * @throws Exception 等待或执行边界失败
     */
    TurnExecutionResult dispatchAndAwait(
            AgentTurn turn, CoreRpcContracts.TurnStartPayload request, CancellationToken cancellation) throws Exception;

    /**
     * 使用同一 Execution 冻结快照执行并等待子 Turn。
     *
     * @param turn 已持久化 Turn
     * @param request 执行参数
     * @param snapshot Execution 权威快照
     * @param cancellation 上层取消信号
     * @return 终态结果
     * @throws Exception 等待或执行边界失败
     */
    TurnExecutionResult dispatchOrchestratedAndAwait(
            AgentTurn turn,
            CoreRpcContracts.TurnStartPayload request,
            AutomationExecutionSnapshot snapshot,
            CancellationToken cancellation)
            throws Exception;
}
