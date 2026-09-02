package com.javaclaw.client.facade;

import java.util.Objects;

import com.javaclaw.api.ThreadId;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CoreRpcContracts;

/** Item Core 方法的强类型 facade。 */
public final class ItemClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public ItemClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 按 sequence 分页读取 ItemEnvelope。
     *
     * @param threadId Thread
     * @param afterSequence 排他游标；从头为 0
     * @param limit 页大小，1 到 1000
     * @return Item 及下一游标
     */
    public CoreRpcContracts.ItemListResult list(ThreadId threadId, long afterSequence, int limit) {
        return connection.query(
                "item/list",
                new CoreRpcContracts.ItemList(threadId, afterSequence, limit),
                CoreRpcContracts.ItemListResult.class);
    }
}
