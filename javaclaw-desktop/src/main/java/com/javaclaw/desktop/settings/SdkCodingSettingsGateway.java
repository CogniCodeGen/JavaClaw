package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.protocol.InputJobRpcContracts;

/** 当前 Desktop 会话的 Coding SDK 适配器，不访问服务端实现或宿主文件系统。 */
public final class SdkCodingSettingsGateway implements CodingSettingsGateway {
    private final DesktopPresenter desktop;

    /**
     * 创建 Coding 设置边界。
     *
     * @param desktop 共享连接和后台执行器
     */
    public SdkCodingSettingsGateway(DesktopPresenter desktop) {
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<Snapshot> load(WorkspaceId workspaceId) {
        return desktop.submitSettingsRequest(client -> {
            var coding = client.builtins().coding();
            return new Snapshot(
                    coding.environment(workspaceId), coding.catalog(workspaceId), coding.toolchains(workspaceId));
        });
    }

    @Override
    public CompletionStage<CodingResults.ExecutionList> executions(WorkspaceId workspaceId) {
        return desktop.submitSettingsRequest(
                client -> client.builtins().coding().executions(workspaceId, Optional.empty(), Optional.empty()));
    }

    @Override
    public CompletionStage<CodingEnvironmentContracts.Environment> save(
            WorkspaceId workspaceId, CodingEnvironmentContracts.EnvironmentSpec spec, CommandOptions options) {
        return desktop.submitSettingsRequest(
                client -> client.builtins().coding().updateEnvironment(workspaceId, spec, options));
    }

    @Override
    public CompletionStage<CodingEnvironmentContracts.InstallAccepted> install(
            WorkspaceId workspaceId, CodingEnvironmentContracts.ToolchainRef reference, CommandOptions options) {
        return desktop.submitSettingsRequest(
                client -> client.builtins().coding().installToolchain(workspaceId, reference, options));
    }

    @Override
    public CompletionStage<InputJobRpcContracts.JobReadResult> job(String jobId) {
        return desktop.submitSettingsRequest(client -> client.extensionJobs().read(jobId));
    }

    @Override
    public CompletionStage<Void> cancel(String jobId, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> {
            client.extensionJobs().cancel(jobId, options);
            return null;
        });
    }
}
