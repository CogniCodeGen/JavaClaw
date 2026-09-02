package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.PromptOptimizationAdoption;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;

/** 仅通过 Java SDK 使用 Prompt 优化用例的生产网关。 */
public final class SdkPromptOptimizationSettingsGateway implements PromptOptimizationSettingsGateway {
    private final DesktopPresenter desktop;

    /**
     * 创建网关。
     *
     * @param desktop 拥有 SDK 会话和后台执行器的 Presenter
     */
    public SdkPromptOptimizationSettingsGateway(DesktopPresenter desktop) {
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<List<Workspace>> workspaces() {
        return desktop.submitSettingsRequest(client -> client.workspaces().list());
    }

    @Override
    public CompletionStage<PromptOptimizationDraft> start(
            WorkspaceId workspaceId,
            AgentProfileRef profile,
            boolean billingConfirmed,
            String confirmation,
            CommandOptions options) {
        return desktop.submitSettingsRequest(client ->
                client.promptOptimizations().start(workspaceId, profile, billingConfirmed, confirmation, options));
    }

    @Override
    public CompletionStage<List<PromptOptimizationDraft>> list(WorkspaceId workspaceId) {
        return desktop.submitSettingsRequest(
                client -> client.promptOptimizations().list(workspaceId));
    }

    @Override
    public CompletionStage<PromptOptimizationDraft> read(PromptOptimizationId id) {
        return desktop.submitSettingsRequest(
                client -> client.promptOptimizations().read(id));
    }

    @Override
    public CompletionStage<PromptOptimizationDraft> cancel(
            PromptOptimizationId id, String reason, CommandOptions options) {
        return desktop.submitSettingsRequest(
                client -> client.promptOptimizations().cancel(id, reason, options));
    }

    @Override
    public CompletionStage<PromptOptimizationAdoption> adopt(
            PromptOptimizationId id, boolean adoptionConfirmed, String confirmation, CommandOptions options) {
        return desktop.submitSettingsRequest(
                client -> client.promptOptimizations().adopt(id, adoptionConfirmed, confirmation, options));
    }
}
