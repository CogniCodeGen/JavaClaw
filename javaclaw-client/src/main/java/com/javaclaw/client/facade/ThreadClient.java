package com.javaclaw.client.facade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CoreRpcContracts;

/** Thread Core 方法的强类型 facade。 */
public final class ThreadClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public ThreadClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 列出 Workspace 中的 Thread。
     *
     * @param workspaceId Workspace
     * @return Thread 快照
     */
    public List<ConversationThread> list(WorkspaceId workspaceId) {
        return connection
                .query(
                        "thread/list",
                        new CoreRpcContracts.WorkspaceQuery(workspaceId),
                        CoreRpcContracts.ThreadListResult.class)
                .threads();
    }

    /**
     * 创建根 Thread。
     *
     * @param workspaceId Workspace
     * @param title 标题
     * @param options 幂等键与 Workspace expected revision
     * @return 新 Thread
     */
    public ConversationThread create(WorkspaceId workspaceId, String title, CommandOptions options) {
        return create(workspaceId, Optional.empty(), ThreadExecutionIntent.WORKSPACE, title, options);
    }

    /**
     * 创建根或子 Thread。
     *
     * @param workspaceId Workspace
     * @param parentThreadId 父 Thread；根 Thread 为空
     * @param executionIntent 服务端可验证的执行隔离意图
     * @param title 标题
     * @param options 幂等键与 Workspace expected revision
     * @return 新 Thread
     */
    public ConversationThread create(
            WorkspaceId workspaceId,
            Optional<ThreadId> parentThreadId,
            ThreadExecutionIntent executionIntent,
            String title,
            CommandOptions options) {
        return connection.command(
                "thread/create",
                new CoreRpcContracts.ThreadCreatePayload(workspaceId, parentThreadId, executionIntent, title),
                options,
                ConversationThread.class);
    }

    /**
     * 读取一个 Thread。
     *
     * @param threadId Thread
     * @return Thread 快照
     */
    public ConversationThread read(ThreadId threadId) {
        return connection.query("thread/read", new CoreRpcContracts.ThreadQuery(threadId), ConversationThread.class);
    }
}
