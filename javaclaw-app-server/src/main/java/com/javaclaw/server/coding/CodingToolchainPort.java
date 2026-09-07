package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.extension.spi.ExtensionJobRegistrar;
import com.javaclaw.extension.spi.ExtensionRequest;

/** 应用托管安装与活动租约边界；安装不执行项目代码，也不授予项目权限。 */
public interface CodingToolchainPort extends AutoCloseable {
    /** @return 经过发行审阅并适用于本机的固定目录 */
    CodingEnvironmentContracts.Catalog catalog();

    /**
     * 查询当前 Workspace 的可见安装记录。
     *
     * @param workspaceId 已验证 Workspace
     * @return 真实安装状态
     */
    CodingEnvironmentContracts.InstalledList installed(WorkspaceId workspaceId);

    /**
     * 创建或复用工具链安装 Job；请求仅接受目录引用。
     *
     * @param request 已验证管理命令
     * @param cancellation 请求取消信号
     * @return 已持久受理的 Job
     * @throws Exception 目录、幂等或持久化失败
     */
    CodingEnvironmentContracts.InstallAccepted install(ExtensionRequest request, CancellationToken cancellation)
            throws Exception;

    /**
     * 为已冻结版本获取只读安装租约，缺失或损坏时明确失败。
     *
     * @param workspaceId 权威 Workspace
     * @param references 需要的精确版本
     * @return 调用者关闭的租约
     * @throws Exception 任意安装不满足要求
     */
    Lease acquire(WorkspaceId workspaceId, List<CodingEnvironmentContracts.ToolchainRef> references) throws Exception;

    /**
     * 注册固定的安装 executor。
     *
     * @param registrar 平台 Job Supervisor
     */
    void registerJobs(ExtensionJobRegistrar registrar);

    /** 停止新租约并关闭安装资源，不删除活动版本。 */
    @Override
    void close() throws Exception;

    /**
     * 固定制品与真实安装根。
     *
     * @param artifact 可信发行清单
     * @param root 应用托管的只读绝对目录
     */
    record InstalledArtifact(CodingEnvironmentContracts.ToolchainArtifact artifact, Path root) {}

    /** 活动执行拥有的工具链引用；关闭时只释放计数。 */
    interface Lease extends AutoCloseable {
        /** @return 已验证的不可变安装集合 */
        Map<CodingEnvironmentContracts.ToolchainKind, InstalledArtifact> installations();

        /** 释放本次执行对安装的引用；重复调用安全。 */
        @Override
        void close();
    }
}
