package com.javaclaw.client.facade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.TurnId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.InputJobRpcContracts;

/** Turn 用户输入请求的查询与决议 facade。 */
public final class InputClient {
    private final RpcClientConnection connection;

    /**
     * 创建输入 facade。
     *
     * @param connection 已初始化连接
     */
    public InputClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 列出输入请求。
     *
     * @param turnId 可选 Turn 过滤
     * @param includeResolved 是否包含终态
     * @return 按创建时间排序的请求
     */
    public List<InputRequestRecord> list(Optional<TurnId> turnId, boolean includeResolved) {
        return connection
                .query(
                        "turn/input/list",
                        new InputJobRpcContracts.InputListPayload(turnId, includeResolved),
                        InputJobRpcContracts.InputListResult.class)
                .requests();
    }

    /**
     * 提交结构化用户输入。
     *
     * @param requestId 输入请求 ID
     * @param response 符合请求 Schema 的规范对象
     * @param options 幂等键与请求当前 revision
     * @return 终态输入请求
     */
    public InputRequestRecord resolve(String requestId, CanonicalPayload response, CommandOptions options) {
        return connection.command(
                "turn/input/resolve",
                new InputJobRpcContracts.InputResolvePayload(requestId, response),
                options,
                InputRequestRecord.class);
    }
}
