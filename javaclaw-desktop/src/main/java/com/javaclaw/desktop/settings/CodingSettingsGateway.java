package com.javaclaw.desktop.settings;

import java.util.concurrent.CompletionStage;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.InputJobRpcContracts;

/** Coding 设置通过 SDK 管理环境与无项目脚本的工具链 Job。 */
public interface CodingSettingsGateway {
    /**
     * 读取环境、可信目录与安装状态。
     *
     * @param workspaceId 已冻结的 Workspace
     * @return 在 UI 调度器上完成的完整快照
     */
    CompletionStage<Snapshot> load(WorkspaceId workspaceId);

    /**
     * 读取当前 Workspace 最近执行的权威状态；不启动项目进程。
     *
     * @param workspaceId 当前 Workspace 过滤
     * @return 最多 100 条执行摘要
     */
    CompletionStage<CodingResults.ExecutionList> executions(WorkspaceId workspaceId);

    /**
     * 保存环境；不执行依赖准备。
     *
     * @param workspaceId 已冻结的 Workspace
     * @param spec 草稿配置
     * @param options 乐观锁与幂等键
     * @return 新环境
     */
    CompletionStage<CodingEnvironmentContracts.Environment> save(
            WorkspaceId workspaceId, CodingEnvironmentContracts.EnvironmentSpec spec, CommandOptions options);

    /**
     * 安装可信工具链。
     *
     * @param workspaceId 已冻结的 Workspace
     * @param reference 可信目录精确引用
     * @param options 幂等键
     * @return 可观察的后台 Job
     */
    CompletionStage<CodingEnvironmentContracts.InstallAccepted> install(
            WorkspaceId workspaceId, CodingEnvironmentContracts.ToolchainRef reference, CommandOptions options);

    /**
     * 读取工具链 Job 进度。
     *
     * @param jobId 服务端返回的 Job 标识
     * @return 工作单元与状态
     */
    CompletionStage<InputJobRpcContracts.JobReadResult> job(String jobId);

    /**
     * 取消正在安装的工具链 Job。
     *
     * @param jobId 服务端返回的 Job 标识
     * @param options 当前 Job revision 与幂等键
     * @return 完成确认
     */
    CompletionStage<Void> cancel(String jobId, CommandOptions options);

    /**
     * 完整配置快照。
     *
     * @param environment 当前环境与 revision
     * @param catalog 当前平台可信工具链目录
     * @param installed 已安装工具链状态
     */
    record Snapshot(
            CodingEnvironmentContracts.Environment environment,
            CodingEnvironmentContracts.Catalog catalog,
            CodingEnvironmentContracts.InstalledList installed) {}
}
