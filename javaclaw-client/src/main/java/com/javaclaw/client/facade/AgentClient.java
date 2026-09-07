package com.javaclaw.client.facade;

import java.util.Objects;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.TurnId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CollaborationRpcContracts;

/** 子智能体协作 SDK；父预算预留、实时权限与取消由 App Server 统一执行。 */
public final class AgentClient {
    private final RpcClientConnection connection;

    /**
     * 创建协作客户端。
     *
     * @param connection 已初始化的协议连接
     */
    public AgentClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 创建子智能体并原子预留父 Turn 预算。
     *
     * @param payload 父 Turn、角色类型及独立执行选择
     * @param options 父 Turn 当前 revision 与幂等键
     * @return 子 Thread、Turn 及安全配置摘要
     */
    public CollaborationRpcContracts.SpawnResult spawn(
            CollaborationRpcContracts.SpawnPayload payload, CommandOptions options) {
        return connection.command("agent/spawn", payload, options, CollaborationRpcContracts.SpawnResult.class);
    }

    /**
     * 读取子 Turn 当前状态，用于等待任务推进。
     *
     * @param turnId 子 Turn 标识
     * @return 服务端最新 Turn 快照
     */
    public AgentTurn waitFor(TurnId turnId) {
        return connection.query("agent/wait", new CollaborationRpcContracts.WaitPayload(turnId), AgentTurn.class);
    }

    /**
     * 请求中断子 Turn。
     *
     * @param turnId 子 Turn 标识
     * @param reason 脱敏取消原因
     * @param options 子 Turn 当前 revision 与可重试幂等键
     * @return 已持久化取消状态
     */
    public AgentTurn interrupt(TurnId turnId, String reason, CommandOptions options) {
        return connection.command(
                "agent/interrupt",
                new CollaborationRpcContracts.InterruptPayload(turnId, reason),
                options,
                AgentTurn.class);
    }
}
