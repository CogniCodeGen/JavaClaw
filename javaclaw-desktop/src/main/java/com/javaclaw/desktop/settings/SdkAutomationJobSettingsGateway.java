package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJobCursor;
import com.javaclaw.protocol.InputJobRpcContracts;

/** 通过当前 Desktop SDK 会话管理可恢复 Extension Job。 */
public final class SdkAutomationJobSettingsGateway implements AutomationJobSettingsGateway {
    private final DesktopPresenter desktop;

    /**
     * 创建 SDK Job 网关。
     *
     * @param desktop 拥有当前 Java SDK 会话和后台执行器的 Presenter
     */
    public SdkAutomationJobSettingsGateway(DesktopPresenter desktop) {
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<List<Workspace>> workspaces() {
        return desktop.submitSettingsRequest(client -> client.workspaces().list());
    }

    @Override
    public CompletionStage<InputJobRpcContracts.JobListResult> jobs(
            Optional<WorkspaceId> workspaceId,
            Optional<String> extensionId,
            Set<ExecutionState> states,
            Optional<ExtensionJobCursor> after,
            int limit) {
        return desktop.submitSettingsRequest(
                client -> client.extensionJobs().list(workspaceId, extensionId, states, after, limit));
    }

    @Override
    public CompletionStage<InputJobRpcContracts.JobReadResult> job(String jobId) {
        return desktop.submitSettingsRequest(client -> client.extensionJobs().read(jobId));
    }

    @Override
    public CompletionStage<ExtensionExecutionReceipt> pause(ExtensionExecutionReceipt job, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.extensionJobs().pause(job.id(), options));
    }

    @Override
    public CompletionStage<ExtensionExecutionReceipt> resume(ExtensionExecutionReceipt job, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.extensionJobs().resume(job.id(), options));
    }

    @Override
    public CompletionStage<ExtensionExecutionReceipt> cancel(ExtensionExecutionReceipt job, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.extensionJobs().cancel(job.id(), options));
    }
}
