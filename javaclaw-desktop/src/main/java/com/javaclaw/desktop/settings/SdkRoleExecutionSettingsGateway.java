package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileExport;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;

/** 角色与独立执行配置的 SDK 委托，共享管理中心会话和已有 Bundle 网关。 */
abstract class SdkRoleExecutionSettingsGateway extends SdkBundleSettingsGateway implements CoreSettingsGateway {
    private final DesktopPresenter desktop;

    SdkRoleExecutionSettingsGateway(DesktopPresenter desktop) {
        super(desktop);
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<List<AgentRole>> roles() {
        return desktop.submitSettingsRequest(client -> client.roles().list());
    }

    @Override
    public CompletionStage<AgentRole> role(AgentRoleRef reference) {
        AgentRoleRef checked = Objects.requireNonNull(reference, "reference");
        return desktop.submitSettingsRequest(client -> client.roles().read(checked.id(), checked.revision()));
    }

    @Override
    public CompletionStage<AgentRole> createRole(String id, AgentRoleSpec spec, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.roles().create(id, spec, options));
    }

    @Override
    public CompletionStage<AgentRole> updateRole(
            String id, AgentRoleSpec spec, RoleLifecycle lifecycle, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.roles().update(id, spec, lifecycle, options));
    }

    @Override
    public CompletionStage<AgentRole> archiveRole(String id, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.roles().archive(id, options));
    }

    @Override
    public CompletionStage<AgentRoleFilePreview> previewRoleImport(
            String id, String content, AgentRoleFileFormat format) {
        return desktop.submitSettingsRequest(client -> client.roles().importPreview(id, content, format));
    }

    @Override
    public CompletionStage<AgentRole> commitRoleImport(
            String previewId, Optional<ProviderRef> mapping, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.roles().importCommit(previewId, mapping, options));
    }

    @Override
    public CompletionStage<AgentRoleFileExport> exportRole(AgentRoleRef reference, AgentRoleFileFormat format) {
        return desktop.submitSettingsRequest(client -> client.roles().export(reference, format));
    }

    @Override
    public CompletionStage<Optional<ExecutionConfiguration>> executionDefaults(Optional<WorkspaceId> workspaceId) {
        return desktop.submitSettingsRequest(client -> client.executions().readDefaults(workspaceId));
    }

    @Override
    public CompletionStage<ExecutionConfiguration> updateExecutionDefaults(
            Optional<WorkspaceId> workspaceId, ExecutionOverrides execution, CommandOptions options) {
        return desktop.submitSettingsRequest(
                client -> client.executions().updateDefaults(workspaceId, execution, options));
    }

    @Override
    public CompletionStage<Optional<ExecutionConfiguration>> threadExecution(
            WorkspaceId workspaceId, ThreadId threadId) {
        return desktop.submitSettingsRequest(client -> client.executions().readThread(workspaceId, threadId));
    }

    @Override
    public CompletionStage<PromptManifestPreview> previewExecution(
            WorkspaceId workspaceId, ExecutionOverrides execution) {
        return desktop.submitSettingsRequest(
                client -> client.prompts().preview(workspaceId, Optional.empty(), execution));
    }

    @Override
    public CompletionStage<AgentRole> cloneRole(AgentRoleRef source, String id, String name, CommandOptions options) {
        return desktop.submitSettingsRequest(client -> client.roles().clone(source, id, name, options));
    }
}
