package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;

/** 仅通过 Java SDK 访问 Memory 学习设置的生产网关。 */
public final class SdkLearningSettingsGateway implements LearningSettingsGateway {
    private final DesktopPresenter desktop;

    /**
     * 创建网关。
     *
     * @param desktop 拥有 SDK 会话和后台执行器的 Presenter
     */
    public SdkLearningSettingsGateway(DesktopPresenter desktop) {
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<List<Workspace>> workspaces() {
        return desktop.submitSettingsRequest(client -> client.workspaces().list());
    }

    @Override
    public CompletionStage<MemoryContracts.LearningSettings> read(WorkspaceId workspaceId) {
        return desktop.submitSettingsRequest(
                client -> client.builtins().memories().learningSettings(workspaceId));
    }

    @Override
    public CompletionStage<MemoryContracts.LearningSettings> update(
            WorkspaceId workspaceId, MemoryContracts.LearningPolicy policy, CommandOptions options) {
        MemoryContracts.LearningSettingsUpdate update = new MemoryContracts.LearningSettingsUpdate(policy);
        return desktop.submitSettingsRequest(
                client -> client.builtins().memories().updateLearningSettings(workspaceId, update, options));
    }
}
