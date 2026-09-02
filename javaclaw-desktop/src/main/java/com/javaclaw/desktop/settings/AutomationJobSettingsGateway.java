package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJobCursor;
import com.javaclaw.protocol.InputJobRpcContracts;

/** 设置中心访问可恢复 Extension Job 的强类型异步 SDK 边界。 */
public interface AutomationJobSettingsGateway {
    /** @return 可用于 Job 过滤的 Workspace 目录 */
    CompletionStage<List<Workspace>> workspaces();

    /**
     * 分页读取脱敏 Job 摘要。
     *
     * @param workspaceId 可选 Workspace 过滤
     * @param extensionId 可选 Extension 过滤
     * @param states 状态过滤；空集合表示全部
     * @param after 稳定 keyset 游标
     * @param limit 页大小
     * @return 当前页和下一页游标
     */
    CompletionStage<InputJobRpcContracts.JobListResult> jobs(
            Optional<WorkspaceId> workspaceId,
            Optional<String> extensionId,
            Set<ExecutionState> states,
            Optional<ExtensionJobCursor> after,
            int limit);

    /**
     * 读取一个 Job 的权威摘要和工作单元时间线。
     *
     * @param jobId Job 标识
     * @return 不包含恢复 payload 的详情
     */
    CompletionStage<InputJobRpcContracts.JobReadResult> job(String jobId);

    /** @param job 当前 Job @param options 幂等键与预期 revision @return 暂停后的摘要 */
    CompletionStage<ExtensionExecutionReceipt> pause(ExtensionExecutionReceipt job, CommandOptions options);

    /** @param job 当前 Job @param options 幂等键与预期 revision @return 恢复后的摘要 */
    CompletionStage<ExtensionExecutionReceipt> resume(ExtensionExecutionReceipt job, CommandOptions options);

    /** @param job 当前 Job @param options 幂等键与预期 revision @return 取消后的摘要 */
    CompletionStage<ExtensionExecutionReceipt> cancel(ExtensionExecutionReceipt job, CommandOptions options);
}
