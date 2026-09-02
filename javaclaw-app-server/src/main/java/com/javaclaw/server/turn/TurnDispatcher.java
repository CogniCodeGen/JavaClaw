package com.javaclaw.server.turn;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.server.persistence.TurnStartRequest;

/** 已持久化 Turn 的调度端口；Provider 与权限配置必须在写事务前检查。 */
public interface TurnDispatcher {
    /**
     * 在创建 Turn 前检查模型端点和服务器持有的权限配置。
     *
     * @param request 启动参数
     */
    TurnStartRequest resolve(CoreRpcContracts.TurnStartPayload request, CorePayloads.Message message);

    /**
     * 提交已经持久化的 QUEUED Turn；实现不得阻塞 RPC 读取线程，并须按 Turn ID 幂等。
     *
     * @param turn Turn
     * @param request 启动参数
     */
    void dispatch(AgentTurn turn, CoreRpcContracts.TurnStartPayload request);

    /**
     * 从持久用户消息和冻结引用重建 QUEUED/RUNNING Turn 的执行栈。
     *
     * @param turnId Turn
     */
    void resume(TurnId turnId);

    /**
     * 将已持久化的取消请求发布给活动执行；没有活动任务时实现负责收敛终态。
     *
     * @param turnId Turn
     * @param reason 脱敏原因
     */
    void cancel(TurnId turnId, String reason);
}
