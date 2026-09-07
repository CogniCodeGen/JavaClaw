package com.javaclaw.client.extension;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.builtin.contracts.DependencyEvidence;
import com.javaclaw.client.CommandOptions;

/** Coding 环境管理与已授权执行事实的 SDK；项目文件与进程操作只由当前 Turn 的工具调用发起。 */
public final class CodingExtensionClient {
    private final BuiltinClientCalls calls;

    /**
     * 创建 Coding 客户端。
     *
     * @param extensions 当前连接的通用扩展客户端
     */
    public CodingExtensionClient(ExtensionClient extensions) {
        calls = new BuiltinClientCalls(Objects.requireNonNull(extensions, "extensions"), CodingContracts.EXTENSION_ID);
    }

    /**
     * 查询最近最多 100 条已授权执行；Thread/Turn 仅收窄范围，不授予额外权限。
     *
     * @param workspaceId 当前 Workspace
     * @param threadId 可选 Thread 过滤
     * @param turnId 可选 Turn 过滤
     * @return 最近执行及已保留输出长度
     */
    public CodingResults.ExecutionList executions(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, Optional<TurnId> turnId) {
        return calls.query(
                workspaceId,
                threadId,
                turnId,
                "execution/list",
                new CodingEnvironmentContracts.Empty(),
                CodingResults.ExecutionList.class);
    }

    /**
     * 读取运行中或已完成命令的有界输出页。
     *
     * @param workspaceId 当前 Workspace
     * @param request 服务端资源标识及字节游标
     * @return 按原始帧顺序推进游标的输出，末页不推进空游标
     */
    public CodingResults.Output commandOutput(WorkspaceId workspaceId, CodingResults.OutputRead request) {
        return calls.query(workspaceId, "command/output", request, CodingResults.Output.class);
    }

    /**
     * 读取当前平台可安装的应用托管工具链目录。
     *
     * @param workspaceId 已选择的 Workspace
     * @return 经过平台发行清单校验的目录
     */
    public CodingEnvironmentContracts.Catalog catalog(WorkspaceId workspaceId) {
        return calls.query(
                workspaceId,
                "toolchain/catalog",
                new CodingEnvironmentContracts.Empty(),
                CodingEnvironmentContracts.Catalog.class);
    }

    /**
     * 读取托管工具链安装状态。
     *
     * @param workspaceId 已选择的 Workspace
     * @return 安装状态；不会执行项目脚本
     */
    public CodingEnvironmentContracts.InstalledList toolchains(WorkspaceId workspaceId) {
        return calls.query(
                workspaceId,
                "toolchain/list",
                new CodingEnvironmentContracts.Empty(),
                CodingEnvironmentContracts.InstalledList.class);
    }

    /**
     * 读取 Workspace 的 Coding 环境。
     *
     * @param workspaceId 已选择的 Workspace
     * @return revision 为零表示尚未保存的默认环境
     */
    public CodingEnvironmentContracts.Environment environment(WorkspaceId workspaceId) {
        return calls.query(
                workspaceId,
                "environment/read",
                new CodingEnvironmentContracts.Empty(),
                CodingEnvironmentContracts.Environment.class);
    }

    /**
     * 条件保存工具链选择与依赖准备策略；不立即安装依赖或执行项目。
     *
     * @param workspaceId 已冻结的 Workspace
     * @param spec 新环境配置
     * @param options 当前环境 revision 与幂等键
     * @return 已保存的新 revision
     */
    public CodingEnvironmentContracts.Environment updateEnvironment(
            WorkspaceId workspaceId, CodingEnvironmentContracts.EnvironmentSpec spec, CommandOptions options) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        return calls.commandAtRevision(
                workspaceId,
                "environment/update",
                new CodingEnvironmentContracts.EnvironmentUpdate(spec),
                checked,
                CodingEnvironmentContracts.Environment.class,
                Math.addExact(checked.expectedRevision(), 1));
    }

    /**
     * 提交工具链下载、摘要校验与解包 Job；该 Job 不执行项目脚本。
     *
     * @param workspaceId 已冻结的 Workspace
     * @param reference 发行目录中的精确工具链摘要
     * @param options 幂等命令选项
     * @return 可通过 ExtensionJobClient 观察与取消的 Job
     */
    public CodingEnvironmentContracts.InstallAccepted installToolchain(
            WorkspaceId workspaceId, CodingEnvironmentContracts.ToolchainRef reference, CommandOptions options) {
        return calls.command(
                workspaceId,
                "toolchain/install",
                new CodingEnvironmentContracts.InstallRequest(reference),
                options,
                CodingEnvironmentContracts.InstallAccepted.class);
    }

    /**
     * 读取已授权的依赖准备结果；资源标识不授予额外的 Turn 权限。
     *
     * @param workspaceId 已冻结的 Workspace
     * @param resourceId 服务端发出的准备资源标识
     * @return 执行结果
     */
    public CodingResults.PreparationResult preparation(WorkspaceId workspaceId, String resourceId) {
        return calls.query(
                workspaceId,
                "preparation/read",
                new CodingResults.ResourceRead(resourceId),
                CodingResults.PreparationResult.class);
    }

    /**
     * 读取独立版本的依赖观察与原始证据引用；不会再次执行准备。
     *
     * @param workspaceId 当前授权 Workspace
     * @param resourceId 服务端准备操作标识
     * @return 包含扫描遗漏的证据，原始内容均为不可信数据
     */
    public DependencyEvidence preparationEvidence(WorkspaceId workspaceId, String resourceId) {
        return calls.query(
                workspaceId,
                "preparation/evidence",
                new CodingResults.ResourceRead(resourceId),
                DependencyEvidence.class);
    }

    /**
     * 读取已授权文件修改的 Diff 与冲突结果。
     *
     * @param workspaceId 已冻结的 Workspace
     * @param resourceId 服务端发出的修改资源标识
     * @return 修改事实；不会重新读取任意宿主路径
     */
    public CodingResults.PatchResult changes(WorkspaceId workspaceId, String resourceId) {
        return calls.query(
                workspaceId,
                "change/list",
                new CodingResults.ResourceRead(resourceId),
                CodingResults.PatchResult.class);
    }

    /**
     * 读取当前授权范围内的终端输出；不提供人工 PTY 输入入口。
     *
     * @param workspaceId 已冻结的 Workspace
     * @param resourceId 服务端发出的终端资源标识
     * @return 终端状态与输出
     */
    public CodingResults.TerminalResult terminal(WorkspaceId workspaceId, String resourceId) {
        return calls.query(
                workspaceId,
                "terminal/read",
                new CodingResults.ResourceRead(resourceId),
                CodingResults.TerminalResult.class);
    }

    /**
     * 读取依赖准备输出的一页。
     *
     * @param workspaceId 已冻结的 Workspace
     * @param request 服务端资源标识和字节游标
     * @return 输出页及下一字节游标
     */
    public CodingResults.Output preparationOutput(WorkspaceId workspaceId, CodingResults.OutputRead request) {
        return calls.query(workspaceId, "preparation/output", request, CodingResults.Output.class);
    }

    /**
     * 读取终端输出的一页。
     *
     * @param workspaceId 已冻结的 Workspace
     * @param request 服务端资源标识和字节游标
     * @return 终端状态、输出页及下一字节游标
     */
    public CodingResults.TerminalResult terminalOutput(WorkspaceId workspaceId, CodingResults.OutputRead request) {
        return calls.query(workspaceId, "terminal/output", request, CodingResults.TerminalResult.class);
    }
}
