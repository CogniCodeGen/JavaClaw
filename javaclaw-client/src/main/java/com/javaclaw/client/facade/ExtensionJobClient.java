package com.javaclaw.client.facade;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJobCursor;
import com.javaclaw.protocol.InputJobRpcContracts;

/** 可恢复 Extension Job 的只读详情与生命周期管理 facade。 */
public final class ExtensionJobClient {
    private final RpcClientConnection connection;

    /**
     * 创建 Job facade。
     *
     * @param connection 已初始化连接
     */
    public ExtensionJobClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 列出 Job。
     *
     * @param workspaceId 可选 Workspace 过滤
     * @param extensionId 可选扩展标识
     * @param states 状态过滤；空集合表示全部
     * @param after 上一页返回的稳定游标
     * @param limit 页大小，1 到 200
     * @return 当前页与下一页游标
     */
    public InputJobRpcContracts.JobListResult list(
            Optional<WorkspaceId> workspaceId,
            Optional<String> extensionId,
            Set<ExecutionState> states,
            Optional<ExtensionJobCursor> after,
            int limit) {
        return connection.query(
                "extension/job/list",
                new InputJobRpcContracts.JobListPayload(workspaceId, extensionId, states, after, limit),
                InputJobRpcContracts.JobListResult.class);
    }

    /**
     * 读取 Job 与工作单元时间线。
     *
     * @param jobId Job ID
     * @return 权威详情
     */
    public InputJobRpcContracts.JobReadResult read(String jobId) {
        return connection.query(
                "extension/job/read",
                new InputJobRpcContracts.JobReadPayload(jobId),
                InputJobRpcContracts.JobReadResult.class);
    }

    /**
     * 暂停 Job。
     *
     * @param jobId Job ID
     * @param options 幂等键与当前 revision
     * @return 已暂停快照
     */
    public ExtensionExecutionReceipt pause(String jobId, CommandOptions options) {
        return mutate("extension/job/pause", jobId, options);
    }

    /**
     * 恢复 Job。
     *
     * @param jobId Job ID
     * @param options 幂等键与当前 revision
     * @return 已排队快照
     */
    public ExtensionExecutionReceipt resume(String jobId, CommandOptions options) {
        return mutate("extension/job/resume", jobId, options);
    }

    /**
     * 取消 Job。
     *
     * @param jobId Job ID
     * @param options 幂等键与当前 revision
     * @return 已取消快照
     */
    public ExtensionExecutionReceipt cancel(String jobId, CommandOptions options) {
        return mutate("extension/job/cancel", jobId, options);
    }

    private ExtensionExecutionReceipt mutate(String method, String jobId, CommandOptions options) {
        return connection.command(
                method, new InputJobRpcContracts.JobMutationPayload(jobId), options, ExtensionExecutionReceipt.class);
    }
}
