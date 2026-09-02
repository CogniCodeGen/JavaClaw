package com.javaclaw.client.facade;

import java.util.Objects;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.TurnId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CoreRpcContracts;

/** Turn Core 方法的强类型 facade。 */
public final class TurnClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public TurnClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 启动一个 Turn。
     *
     * @param payload 冻结模型、预算、权限和用户输入
     * @param options 幂等键与 Thread expected revision
     * @return 已持久化 Turn
     */
    public AgentTurn start(CoreRpcContracts.TurnStartPayload payload, CommandOptions options) {
        return connection.command("turn/start", payload, options, AgentTurn.class);
    }

    /**
     * 读取一个 Turn。
     *
     * @param turnId Turn
     * @return Turn 快照
     */
    public AgentTurn read(TurnId turnId) {
        return connection.query("turn/read", new CoreRpcContracts.TurnQuery(turnId), AgentTurn.class);
    }

    /**
     * 请求取消 Turn。
     *
     * <p>服务端先持久化取消意图，再通知运行中的 Harness。网络重试必须复用同一 {@code options}。
     *
     * @param turnId Turn
     * @param reason 已脱敏的用户可见原因
     * @param options 幂等键与 Turn expected revision
     * @return 已更新 Turn
     */
    public AgentTurn cancel(TurnId turnId, String reason, CommandOptions options) {
        return connection.command(
                "turn/cancel", new CoreRpcContracts.TurnCancelPayload(turnId, reason), options, AgentTurn.class);
    }
}
