package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;

/** 复用 Desktop 会话的聊天配置边界，不持有 UI 控件或服务端对象。 */
abstract class SdkChatConfigurationGateway extends SdkBundleSettingsGateway implements CoreSettingsGateway {
    private final DesktopPresenter desktop;

    SdkChatConfigurationGateway(DesktopPresenter desktop) {
        super(desktop);
        this.desktop = desktop;
    }

    @Override
    public CompletionStage<ExecutionPreview> previewChatExecution(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, ExecutionOverrides execution) {
        return desktop.submitSettingsRequest(client -> client.executions().preview(workspaceId, threadId, execution));
    }

    @Override
    public CompletionStage<Void> useModel(
            Optional<WorkspaceId> workspaceId, Optional<ThreadId> threadId, ProviderRef model) {
        return desktop.useModel(workspaceId, threadId, model);
    }

    @Override
    public CompletionStage<ExecutionConfiguration> rememberChatSelection(
            WorkspaceId workspaceId,
            ThreadId threadId,
            ExecutionOverrides execution,
            CommandOptions options,
            boolean remember) {
        return desktop.rememberChatSelection(workspaceId, threadId, execution, options, remember);
    }

    @Override
    public CompletionStage<Workspace> createModelWorkspace(String name, Path root) {
        return desktop.createModelWorkspace(name, root);
    }
}
