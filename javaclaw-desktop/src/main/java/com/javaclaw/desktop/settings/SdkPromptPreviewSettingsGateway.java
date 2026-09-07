package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.DesktopPresenter;

/** 仅通过 Java SDK 读取 Prompt provenance 的生产网关。 */
public final class SdkPromptPreviewSettingsGateway implements PromptPreviewSettingsGateway {
    private final DesktopPresenter desktop;

    /**
     * 创建网关。
     *
     * @param desktop 拥有 SDK 会话和后台执行器的 Presenter
     */
    public SdkPromptPreviewSettingsGateway(DesktopPresenter desktop) {
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<List<Workspace>> workspaces() {
        return desktop.submitSettingsRequest(client -> client.workspaces().list());
    }

    @Override
    public CompletionStage<PromptManifestPreview> preview(WorkspaceId workspaceId, AgentRoleRef profile) {
        return desktop.submitSettingsRequest(client -> client.prompts()
                .preview(
                        workspaceId,
                        Optional.empty(),
                        new ExecutionOverrides(
                                Optional.of(profile),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty())));
    }
}
