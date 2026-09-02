package com.javaclaw.client.facade;

import java.nio.file.Path;
import java.util.Objects;

import com.javaclaw.api.RolloutManifest;
import com.javaclaw.api.ThreadId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CoreRpcContracts;

/** Rollout 只读导出的强类型 facade。 */
public final class RolloutClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public RolloutClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 从一致性快照导出可校验 JSONL。
     *
     * @param threadId Thread
     * @param outputFile 不会覆盖的目标文件
     * @param options 幂等键与 Thread expected revision
     * @return 完整性清单
     */
    public RolloutManifest export(ThreadId threadId, Path outputFile, CommandOptions options) {
        return connection.command(
                "thread/rollout/export",
                new CoreRpcContracts.RolloutExportPayload(threadId, outputFile),
                options,
                RolloutManifest.class);
    }
}
