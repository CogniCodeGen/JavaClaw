package com.javaclaw.client.facade;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.ExecutionRpcContracts;

/** 独立执行默认值和 Thread 覆盖的 SDK；所有读写只经过 App Server。 */
public final class ExecutionClient {
    private final RpcClientConnection connection;

    /**
     * 创建执行配置客户端。
     *
     * @param connection 已初始化的 RPC 连接
     */
    public ExecutionClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 读取只用于初始化新 Thread 的最近模型和思考；不读取安装默认。
     *
     * @return 最近选择的独立版本；缺失时新 Thread 应继承项目设置
     */
    public Optional<ExecutionConfiguration> readRecent() {
        return connection
                .query(
                        "execution/recent/read",
                        new ExecutionRpcContracts.RecentReadPayload(),
                        ExecutionRpcContracts.ReadResult.class)
                .configuration();
    }

    /**
     * 保存最近模型和思考，不改变安装默认或已有 Thread 的继承配置。
     *
     * @param execution 仅 provider 和 reasoning 可填写，其他字段必须为空
     * @param options 最近选择自身的 revision 与幂等键
     * @return 已提交的独立最近选择版本，作用域字段均为空
     */
    public ExecutionConfiguration updateRecent(ExecutionOverrides execution, CommandOptions options) {
        return connection.command(
                "execution/recent/update",
                new ExecutionRpcContracts.RecentUpdatePayload(execution),
                options,
                ExecutionConfiguration.class);
    }

    /**
     * 只读解析下一 Turn 的有效模型、思考、锁定状态与配置阻塞项，不调用模型。
     *
     * @param workspaceId 固定的目标 Workspace
     * @param threadId 可选已有 Thread，由服务端校验归属
     * @param execution 本次临时选择；缺省字段沿用已保存配置
     * @return 轻量本地配置快照；实际启动仍由服务端重新校验
     */
    public ExecutionPreview preview(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, ExecutionOverrides execution) {
        return connection.query(
                "execution/preview",
                new ExecutionRpcContracts.PreviewPayload(workspaceId, threadId, execution),
                ExecutionPreview.class);
    }

    /**
     * 读取安装或 Workspace 的直接配置。
     *
     * @param workspaceId 空值表示安装默认
     * @return 尚未配置时为空，不隐式返回上级配置
     */
    public Optional<ExecutionConfiguration> readDefaults(Optional<WorkspaceId> workspaceId) {
        return connection
                .query(
                        "execution/default/read",
                        new ExecutionRpcContracts.DefaultReadPayload(workspaceId),
                        ExecutionRpcContracts.ReadResult.class)
                .configuration();
    }

    /**
     * 替换安装或 Workspace 的直接配置，只影响新 Turn。
     *
     * @param workspaceId 空值表示安装默认
     * @param execution 独立执行覆盖
     * @param options 当前配置 revision 与幂等键
     * @return 已提交配置版本
     */
    public ExecutionConfiguration updateDefaults(
            Optional<WorkspaceId> workspaceId, ExecutionOverrides execution, CommandOptions options) {
        return connection.command(
                "execution/default/update",
                new ExecutionRpcContracts.DefaultUpdatePayload(workspaceId, execution),
                options,
                ExecutionConfiguration.class);
    }

    /**
     * 读取子智能体专用的模型与推理默认值。
     *
     * @param workspaceId 空值表示安装范围，有值表示 Workspace 范围
     * @return 直接配置；空值表示尚未配置，运行时回退到父 Turn
     */
    public Optional<ExecutionConfiguration> readSubagentDefaults(Optional<WorkspaceId> workspaceId) {
        return connection
                .query(
                        "execution/subagent/read",
                        new ExecutionRpcContracts.DefaultReadPayload(workspaceId),
                        ExecutionRpcContracts.ReadResult.class)
                .configuration();
    }

    /**
     * 替换子智能体模型与推理默认值，仅影响新的子任务。
     *
     * @param workspaceId 空值表示安装范围，有值表示 Workspace 范围
     * @param execution 仅 provider 与 reasoning 可填写，其他字段必须为空
     * @param options 当前子智能体默认配置的 revision 与幂等键
     * @return 已提交的独立配置版本
     */
    public ExecutionConfiguration updateSubagentDefaults(
            Optional<WorkspaceId> workspaceId, ExecutionOverrides execution, CommandOptions options) {
        return connection.command(
                "execution/subagent/update",
                new ExecutionRpcContracts.DefaultUpdatePayload(workspaceId, execution),
                options,
                ExecutionConfiguration.class);
    }

    /**
     * 读取 Thread 直接覆盖。
     *
     * @param workspaceId 所属 Workspace
     * @param threadId Thread
     * @return 未配置时为空
     */
    public Optional<ExecutionConfiguration> readThread(WorkspaceId workspaceId, ThreadId threadId) {
        return connection
                .query(
                        "thread/execution/read",
                        new ExecutionRpcContracts.ThreadReadPayload(workspaceId, threadId),
                        ExecutionRpcContracts.ReadResult.class)
                .configuration();
    }

    /**
     * 替换 Thread 直接覆盖，只影响新 Turn。
     *
     * @param workspaceId 所属 Workspace
     * @param threadId Thread
     * @param execution 独立执行覆盖
     * @param options 当前配置 revision 与幂等键
     * @return 已提交配置版本
     */
    public ExecutionConfiguration updateThread(
            WorkspaceId workspaceId, ThreadId threadId, ExecutionOverrides execution, CommandOptions options) {
        return connection.command(
                "thread/execution/update",
                new ExecutionRpcContracts.ThreadUpdatePayload(workspaceId, threadId, execution),
                options,
                ExecutionConfiguration.class);
    }
}
